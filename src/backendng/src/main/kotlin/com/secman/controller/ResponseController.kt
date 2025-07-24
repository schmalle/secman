package com.secman.controller

import com.secman.domain.*
import com.secman.repository.*
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.annotation.Transactional
import jakarta.persistence.EntityManager
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

@Controller("/api/responses")
@ExecuteOn(TaskExecutors.BLOCKING)
open class ResponseController(
    private val responseRepository: ResponseRepository,
    private val assessmentTokenRepository: AssessmentTokenRepository,
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val requirementRepository: RequirementRepository,
    private val useCaseRepository: UseCaseRepository,
    private val entityManager: EntityManager
) {
    
    private val log = LoggerFactory.getLogger(ResponseController::class.java)

    @Serdeable
    data class SaveResponseRequest(
        @NotNull val requirementId: Long,
        @NotNull val answerType: AnswerType,
        @Nullable val comment: String? = null
    )

    @Serdeable
    data class SubmitAssessmentRequest(
        @Email @NotNull val email: String,
        @Nullable val finalComments: String? = null
    )

    @Serdeable
    data class ErrorResponse(
        val error: String,
        val message: String
    )

    @Serdeable
    data class AssessmentData(
        val assessment: RiskAssessment,
        val requirements: List<Requirement>,
        val responses: List<Response>,
        val isComplete: Boolean,
        val completionPercentage: Int
    )

    @Get("/assessment/{token}")
    @Transactional(readOnly = true)
    open fun getAssessmentByToken(token: String): HttpResponse<*> {
        return try {
            log.debug("Fetching assessment for token: {}", token.take(8) + "...")
            
            val assessmentToken = assessmentTokenRepository.findByToken(token).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment token not found"))
            
            if (!assessmentToken.isValid()) {
                return HttpResponse.badRequest(ErrorResponse("TOKEN_EXPIRED", "Assessment token has expired or been used"))
            }
            
            val assessment = assessmentToken.riskAssessment
            
            // Get requirements for this assessment
            val requirements = getRequirementsForAssessment(assessment)
            
            // Get existing responses
            val responses = responseRepository.findByRiskAssessmentId(assessment.id!!)
            
            // Calculate completion
            val completionPercentage = if (requirements.isNotEmpty()) {
                (responses.size * 100) / requirements.size
            } else {
                0
            }
            
            val assessmentData = AssessmentData(
                assessment = assessment,
                requirements = requirements,
                responses = responses,
                isComplete = responses.size >= requirements.size,
                completionPercentage = completionPercentage
            )
            
            log.debug("Assessment data prepared: {} requirements, {} responses", 
                requirements.size, responses.size)
            HttpResponse.ok(assessmentData)
        } catch (e: Exception) {
            log.error("Error fetching assessment for token", e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post("/{token}/save")
    @Transactional
    open fun saveResponse(token: String, @Valid @Body request: SaveResponseRequest): HttpResponse<*> {
        return try {
            log.debug("Saving response for token: {}", token.take(8) + "...")
            
            val assessmentToken = assessmentTokenRepository.findByToken(token).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment token not found"))
            
            if (!assessmentToken.isValid()) {
                return HttpResponse.badRequest(ErrorResponse("TOKEN_EXPIRED", "Assessment token has expired or been used"))
            }
            
            val assessment = assessmentToken.riskAssessment
            
            // Validate requirement exists
            val requirement = requirementRepository.findById(request.requirementId).orElse(null)
                ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Requirement not found"))
            
            // Check if response already exists for this requirement and assessment
            val existingResponse = responseRepository
                .findByRiskAssessmentIdAndRequirementId(assessment.id!!, request.requirementId)
            
            val response = if (existingResponse != null) {
                // Update existing response
                existingResponse.answerType = request.answerType
                existingResponse.comment = request.comment?.trim()?.takeIf { it.isNotBlank() }
                existingResponse.respondentEmail = assessmentToken.email
                responseRepository.update(existingResponse)
            } else {
                // Create new response
                val newResponse = Response(
                    answerType = request.answerType,
                    comment = request.comment?.trim()?.takeIf { it.isNotBlank() },
                    respondentEmail = assessmentToken.email,
                    riskAssessment = assessment,
                    requirement = requirement
                )
                responseRepository.save(newResponse)
            }
            
            log.info("Saved response for requirement {} in assessment {}", 
                request.requirementId, assessment.id)
            HttpResponse.ok(response)
        } catch (e: Exception) {
            log.error("Error saving response for token", e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post("/{token}/submit")
    @Transactional
    open fun submitAssessment(token: String, @Valid @Body request: SubmitAssessmentRequest): HttpResponse<*> {
        return try {
            log.debug("Submitting assessment for token: {}", token.take(8) + "...")
            
            val assessmentToken = assessmentTokenRepository.findByToken(token).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment token not found"))
            
            if (!assessmentToken.isValid()) {
                return HttpResponse.badRequest(ErrorResponse("TOKEN_EXPIRED", "Assessment token has expired or been used"))
            }
            
            val assessment = assessmentToken.riskAssessment
            val requirements = getRequirementsForAssessment(assessment)
            val responses = responseRepository.findByRiskAssessmentId(assessment.id!!)
            
            // Validate all requirements have been answered
            if (responses.size < requirements.size) {
                return HttpResponse.badRequest(ErrorResponse("INCOMPLETE_ASSESSMENT", 
                    "Assessment is incomplete. ${responses.size} of ${requirements.size} requirements answered."))
            }
            
            // Mark assessment as completed
            assessment.status = "COMPLETED"
            riskAssessmentRepository.update(assessment)
            
            // Mark token as used
            assessmentToken.markAsUsed()
            assessmentTokenRepository.update(assessmentToken)
            
            // TODO: Send completion notification email to requestor
            
            log.info("Assessment {} submitted by {}", assessment.id, request.email)
            HttpResponse.ok(mapOf(
                "message" to "Assessment submitted successfully",
                "assessmentId" to assessment.id,
                "responsesCount" to responses.size
            ))
        } catch (e: Exception) {
            log.error("Error submitting assessment for token", e)
            HttpResponse.serverError<Any>()
        }
    }

    @Get("/assessment/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional(readOnly = true)
    open fun getAllResponses(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching all responses for assessment: {}", id)
            
            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            
            val responses = responseRepository.findByRiskAssessmentId(id)
            
            // Force loading of related entities
            responses.forEach { response ->
                response.requirement.shortreq // Force loading
            }
            
            log.debug("Found {} responses for assessment {}", responses.size, id)
            HttpResponse.ok(responses)
        } catch (e: Exception) {
            log.error("Error fetching responses for assessment: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Get("/assessment/{id}/email/{email}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional(readOnly = true)
    open fun getResponsesByEmail(id: Long, email: String): HttpResponse<*> {
        return try {
            log.debug("Fetching responses for assessment: {} and email: {}", id, email)
            
            val responses = responseRepository.findByRiskAssessmentIdAndEmail(id, email)
            
            // Force loading of related entities
            responses.forEach { response ->
                response.requirement.shortreq // Force loading
            }
            
            log.debug("Found {} responses for assessment {} and email {}", responses.size, id, email)
            HttpResponse.ok(responses)
        } catch (e: Exception) {
            log.error("Error fetching responses for assessment: {} and email: {}", id, email, e)
            HttpResponse.serverError<Any>()
        }
    }

    /**
     * Get requirements for assessment following the same logic as Java backend:
     * 1. Direct requirements from use cases
     * 2. Fallback to requirements from standards via use cases
     * 3. Fallback to all requirements
     */
    private fun getRequirementsForAssessment(assessment: RiskAssessment): List<Requirement> {
        return try {
            if (assessment.useCases.isNotEmpty()) {
                // Get requirements directly associated with use cases
                val requirements = assessment.useCases.flatMap { useCase ->
                    requirementRepository.findByUsecaseId(useCase.id!!)
                }.distinct()
                
                if (requirements.isNotEmpty()) {
                    log.debug("Found {} requirements from direct use case associations", requirements.size)
                    return requirements
                }
                
                // Fallback: Get requirements from standards associated with these use cases
                val standardRequirements = entityManager.createQuery(
                    """
                    SELECT DISTINCT r FROM Requirement r 
                    JOIN r.useCases u 
                    JOIN u.standards s 
                    JOIN s.useCases uc 
                    WHERE uc.id IN :useCaseIds
                    """,
                    Requirement::class.java
                ).setParameter("useCaseIds", assessment.useCases.map { it.id }).resultList
                
                if (standardRequirements.isNotEmpty()) {
                    log.debug("Found {} requirements from standards via use cases", standardRequirements.size)
                    return standardRequirements
                }
            }
            
            // Final fallback: All requirements
            val allRequirements = requirementRepository.findAll()
            log.debug("Using all {} requirements as fallback", allRequirements.size)
            allRequirements
        } catch (e: Exception) {
            log.error("Error getting requirements for assessment", e)
            // Return empty list if there's an error
            emptyList()
        }
    }
}