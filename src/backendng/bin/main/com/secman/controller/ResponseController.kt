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
import io.micronaut.security.authentication.Authentication
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
    private val riskRepository: RiskRepository,
    private val userRepository: UserRepository,
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
    data class BulkSaveResponseRequest(
        @NotNull val responses: List<SaveResponseRequest>
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
        val completionPercentage: Int,
        val canEdit: Boolean = false,
        val canReview: Boolean = false
    )

    @Serdeable
    data class RequirementWithResponse(
        val requirement: Requirement,
        val response: Response?,
        val canRaiseRisk: Boolean = false
    )

    @Serdeable
    data class CreateRiskFromResponseRequest(
        @NotNull val requirementId: Long,
        @NotNull val description: String,
        @NotNull val likelihood: Int = 3,
        @NotNull val impact: Int = 3
    )

    /**
     * Get current user's email from authentication context
     */
    private fun getCurrentUserEmail(authentication: Authentication): String? {
        return try {
            val username = authentication.name
            val userOptional = userRepository.findByUsername(username)
            
            if (userOptional.isEmpty) {
                log.error("User not found for username: {}", username)
                return null
            }
            
            val user = userOptional.get()
            log.debug("Found user email: {} for username: {}", user.email, username)
            user.email
        } catch (e: Exception) {
            log.error("Error getting current user email", e)
            null
        }
    }

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

    @Get("/assessment/{id}/authenticated")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional(readOnly = true)
    open fun getAssessmentAuthenticated(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching assessment for authenticated user: {}", id)
            
            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            
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
            
            // Check permissions - can edit if assessor or respondent, can review if requestor or admin
            val canEdit = assessment.status == "STARTED"
            val canReview = true // In production, check if user is requestor or admin
            
            val assessmentData = AssessmentData(
                assessment = assessment,
                requirements = requirements,
                responses = responses,
                isComplete = responses.size >= requirements.size,
                completionPercentage = completionPercentage,
                canEdit = canEdit,
                canReview = canReview
            )
            
            log.debug("Assessment data prepared: {} requirements, {} responses", 
                requirements.size, responses.size)
            HttpResponse.ok(assessmentData)
        } catch (e: Exception) {
            log.error("Error fetching assessment for authenticated user", e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post("/assessment/{id}/save")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun saveResponseAuthenticated(id: Long, @Valid @Body request: SaveResponseRequest, authentication: Authentication): HttpResponse<*> {
        return try {
            log.debug("Saving response for assessment: {}", id)
            
            // Get current user's email
            val currentUserEmail = getCurrentUserEmail(authentication)
            if (currentUserEmail.isNullOrBlank()) {
                return HttpResponse.badRequest(ErrorResponse("AUTHENTICATION_ERROR", "Unable to determine current user's email. Please log in again."))
            }
            
            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            
            if (assessment.status != "STARTED") {
                return HttpResponse.badRequest(ErrorResponse("ASSESSMENT_LOCKED", "Assessment is not open for editing"))
            }
            
            // Validate requirement exists
            val requirement = requirementRepository.findById(request.requirementId).orElse(null)
                ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Requirement not found"))
            
            // Check if response already exists for this requirement and assessment
            val existingResponse = responseRepository
                .findByRiskAssessmentIdAndRequirementId(assessment.id!!, request.requirementId)
            
            val response = if (existingResponse != null) {
                // Update existing response, preserve or update respondent email
                existingResponse.answerType = request.answerType
                existingResponse.comment = request.comment?.trim()?.takeIf { it.isNotBlank() }
                // Set email if it's not already set
                if (existingResponse.respondentEmail.isNullOrBlank()) {
                    existingResponse.respondentEmail = currentUserEmail
                }
                responseRepository.update(existingResponse)
            } else {
                // Create new response
                val newResponse = Response(
                    answerType = request.answerType,
                    comment = request.comment?.trim()?.takeIf { it.isNotBlank() },
                    respondentEmail = currentUserEmail,
                    riskAssessment = assessment,
                    requirement = requirement
                )
                responseRepository.save(newResponse)
            }
            
            log.info("Saved response for requirement {} in assessment {} with email {}", 
                request.requirementId, assessment.id, currentUserEmail)
            HttpResponse.ok(response)
        } catch (e: Exception) {
            log.error("Error saving response for authenticated user", e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post("/assessment/{id}/bulk-save")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun bulkSaveResponsesAuthenticated(id: Long, @Valid @Body request: BulkSaveResponseRequest, authentication: Authentication): HttpResponse<*> {
        return try {
            log.debug("Bulk saving {} responses for assessment: {}", request.responses.size, id)
            
            // Get current user's email
            val currentUserEmail = getCurrentUserEmail(authentication)
            if (currentUserEmail.isNullOrBlank()) {
                return HttpResponse.badRequest(ErrorResponse("AUTHENTICATION_ERROR", "Unable to determine current user's email. Please log in again."))
            }
            
            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            
            if (assessment.status != "STARTED") {
                return HttpResponse.badRequest(ErrorResponse("ASSESSMENT_LOCKED", "Assessment is not open for editing"))
            }
            
            val savedResponses = mutableListOf<Response>()
            val errors = mutableListOf<String>()
            
            for (responseRequest in request.responses) {
                try {
                    // Validate requirement exists
                    val requirement = requirementRepository.findById(responseRequest.requirementId).orElse(null)
                    if (requirement == null) {
                        log.warn("Skipping invalid requirement ID: {}", responseRequest.requirementId)
                        errors.add("Invalid requirement ID: ${responseRequest.requirementId}")
                        continue
                    }
                    
                    // Check if response already exists for this requirement and assessment
                    val existingResponse = responseRepository
                        .findByRiskAssessmentIdAndRequirementId(assessment.id!!, responseRequest.requirementId)
                    
                    val response = if (existingResponse != null) {
                        // Update existing response
                        existingResponse.answerType = responseRequest.answerType
                        existingResponse.comment = responseRequest.comment?.trim()?.takeIf { it.isNotBlank() }
                        // Set email if it's not already set
                        if (existingResponse.respondentEmail.isNullOrBlank()) {
                            existingResponse.respondentEmail = currentUserEmail
                        }
                        responseRepository.update(existingResponse)
                    } else {
                        // Create new response
                        val newResponse = Response(
                            answerType = responseRequest.answerType,
                            comment = responseRequest.comment?.trim()?.takeIf { it.isNotBlank() },
                            respondentEmail = currentUserEmail,
                            riskAssessment = assessment,
                            requirement = requirement
                        )
                        responseRepository.save(newResponse)
                    }
                    savedResponses.add(response)
                } catch (e: Exception) {
                    log.error("Error saving individual response for requirement {}", responseRequest.requirementId, e)
                    errors.add("Error saving requirement ${responseRequest.requirementId}: ${e.message}")
                }
            }
            
            log.info("Bulk saved {} responses for assessment {} with email {}", savedResponses.size, assessment.id, currentUserEmail)
            
            val result = mutableMapOf<String, Any>(
                "message" to "Responses saved successfully",
                "savedCount" to savedResponses.size
            )
            
            if (errors.isNotEmpty()) {
                result["errors"] = errors
                result["errorCount"] = errors.size
            }
            
            HttpResponse.ok(result)
        } catch (e: Exception) {
            log.error("Error bulk saving responses for authenticated user", e)
            HttpResponse.serverError<Any>()
        }
    }

    @Get("/assessment/{id}/requirements-with-responses")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional(readOnly = true)
    open fun getRequirementsWithResponses(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching requirements with responses for assessment: {}", id)
            
            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            
            val requirements = getRequirementsForAssessment(assessment)
            val responses = responseRepository.findByRiskAssessmentId(id)
            val responseMap = responses.associateBy { it.requirement.id }
            
            val requirementsWithResponses = requirements.map { requirement ->
                val response = responseMap[requirement.id]
                val canRaiseRisk = response?.answerType in listOf(AnswerType.NO, AnswerType.N_A)
                RequirementWithResponse(
                    requirement = requirement,
                    response = response,
                    canRaiseRisk = canRaiseRisk
                )
            }
            
            HttpResponse.ok(requirementsWithResponses)
        } catch (e: Exception) {
            log.error("Error fetching requirements with responses", e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post("/assessment/{id}/create-risk")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun createRiskFromAssessment(id: Long, @Valid @Body request: CreateRiskFromResponseRequest): HttpResponse<*> {
        return try {
            log.debug("Creating risk from assessment: {} for requirement: {}", id, request.requirementId)
            
            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            
            val requirement = requirementRepository.findById(request.requirementId).orElse(null)
                ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Requirement not found"))
            
            // Get the response for this requirement
            val response = responseRepository.findByRiskAssessmentIdAndRequirementId(id, request.requirementId)
            
            // Validate that the response is non-compliant
            if (response == null || response.answerType == AnswerType.YES) {
                return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", 
                    "Can only create risks for non-compliant or not applicable requirements"))
            }
            
            // Determine the asset for the risk
            val asset = when (assessment.assessmentBasisType) {
                AssessmentBasisType.ASSET -> assessment.asset
                AssessmentBasisType.DEMAND -> assessment.demand?.existingAsset
                else -> null
            }
            
            // Create the risk
            val risk = Risk(
                name = "Non-compliance: ${requirement.shortreq.take(100)}",
                description = request.description,
                likelihood = request.likelihood.coerceIn(1, 5),
                impact = request.impact.coerceIn(1, 5),
                status = "OPEN",
                severity = when ((request.likelihood * request.impact)) {
                    in 1..5 -> "LOW"
                    in 6..10 -> "MEDIUM"
                    in 11..15 -> "HIGH"
                    else -> "CRITICAL"
                },
                deadline = assessment.endDate.plusDays(30),
                owner = assessment.assessor,
                asset = asset
            )
            
            val savedRisk = riskRepository.save(risk)
            
            log.info("Created risk {} from assessment {} for requirement {}", 
                savedRisk.id, id, request.requirementId)
            
            HttpResponse.status<Risk>(HttpStatus.CREATED).body(savedRisk)
        } catch (e: Exception) {
            log.error("Error creating risk from assessment", e)
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