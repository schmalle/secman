package com.secman.controller

import com.secman.domain.*
import com.secman.repository.*
import com.secman.service.taskView
import com.secman.service.ReleaseRequirementScopeService
import com.secman.service.RiskAssessmentAccessService
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
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
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
    private val releaseRequirementScopeService: ReleaseRequirementScopeService,
    private val workflow: com.secman.service.AssessmentWorkflowService,
    private val riskAssessmentAccessService: RiskAssessmentAccessService
) {
    
    private val log = LoggerFactory.getLogger(ResponseController::class.java)

    @Serdeable
    data class SaveResponseRequest(
        @NotNull val requirementId: Long,
        @NotNull val answerType: AnswerType,
        @field:Size(max = 4000) @Nullable val comment: String? = null
    )

    @Serdeable
    data class BulkSaveResponseRequest(
        @field:Size(min = 1, max = 200) @NotNull val responses: List<SaveResponseRequest>
    )

    @Serdeable
    data class SubmitAssessmentRequest(
        @Email @NotNull val email: String,
        @field:Size(max = 4000) @Nullable val finalComments: String? = null
    )

    @Serdeable
    data class ErrorResponse(
        val error: String,
        val message: String
    )

    @Serdeable
    data class AssessmentData(
        val assessment: Map<String, Any?>,
        val requirements: List<Requirement>,
        val responses: List<Response>,
        val isComplete: Boolean,
        val completionPercentage: Int,
        val canEdit: Boolean = false,
        val canReview: Boolean = false,
        val acceptance: AssessmentAcceptance? = null
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

    private fun canAccessAssessment(assessment: RiskAssessment, authentication: Authentication) =
        riskAssessmentAccessService.canView(assessment, authentication)

    private fun canAnswerAssessment(assessment: RiskAssessment, authentication: Authentication) =
        riskAssessmentAccessService.canAnswer(assessment, authentication)

    private fun canManageAssessment(assessment: RiskAssessment, authentication: Authentication) =
        riskAssessmentAccessService.canReview(assessment, authentication)

    private fun scopedRequirements(assessment: RiskAssessment, authentication: Authentication) =
        riskAssessmentAccessService.visibleRequirements(assessment, authentication, workflow.requirementsFor(assessment))

    @Get("/assessment/{token:[a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9][a-fA-F0-9]}")
    // Capability URL: the recipient of the assessment-request email holds no SecMan account, so
    // this route must be reachable without login. The 32-hex token IS the authorization — see
    // AccountOnboardingPublicController for the identical pattern. Explicit per A01: a public
    // endpoint is a declared exception, never an omission.
    @Secured(SecurityRule.IS_ANONYMOUS)
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
            val assignment = workflow.tokenAssignment(assessmentToken)
            val requirements = workflow.requirementsFor(assessment).filter {
                riskAssessmentAccessService.permitsRequirement(assignment, it.id!!)
            }
            
            // Get existing responses
            val requirementIds = requirements.mapNotNull { it.id }.toSet()
            val responses = responseRepository.findByRiskAssessmentId(assessment.id!!)
                .filter { it.requirement.id in requirementIds }
            
            // Calculate completion
            val completionPercentage = if (requirements.isNotEmpty()) {
                (responses.size * 100) / requirements.size
            } else {
                0
            }
            
            val assessmentData = AssessmentData(
                assessment = assessmentView(assessment),
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
    // Same capability-URL rationale as getAssessmentByToken above.
    @Secured(SecurityRule.IS_ANONYMOUS)
    @Transactional
    open fun saveResponse(token: String, @Valid @Body request: SaveResponseRequest): HttpResponse<*> {
        val capability = assessmentTokenRepository.findByToken(token).orElse(null)
            ?: return HttpResponse.notFound<Any>()
        return HttpResponse.ok(workflow.saveToken(capability,
            com.secman.service.AssessmentWorkflowService.Answer(request.requirementId, request.answerType, request.comment)))
    }

    @Post("/{token}/submit")
    // Same capability-URL rationale as getAssessmentByToken above.
    @Secured(SecurityRule.IS_ANONYMOUS)
    @Transactional
    open fun submitAssessment(token: String, @Valid @Body request: SubmitAssessmentRequest): HttpResponse<*> {
        val capability = assessmentTokenRepository.findByToken(token).orElse(null)
            ?: return HttpResponse.notFound<Any>()
        if (!capability.email.equals(request.email, true)) return HttpResponse.badRequest<Any>()
        val assessment = workflow.submitToken(capability)
        capability.markAsUsed()
        assessmentTokenRepository.update(capability)
        return HttpResponse.ok(mapOf("assessmentId" to assessment.id, "status" to assessment.status))
    }

    @Get("/assessment/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional(readOnly = true)
    open fun getAllResponses(id: Long, authentication: Authentication): HttpResponse<*> {
        return try {
            log.debug("Fetching all responses for assessment: {}", id)

            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            if (!canAccessAssessment(assessment, authentication)) {
                return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            }

            val allowed = scopedRequirements(assessment, authentication).mapNotNull { it.id }.toSet()
            val responses = responseRepository.findByRiskAssessmentId(id).filter { it.requirement.id in allowed }
            
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
    open fun getResponsesByEmail(id: Long, email: String, authentication: Authentication): HttpResponse<*> {
        return try {
            log.debug("Fetching responses for assessment: {} and email: {}", id, email)

            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            if (!canAccessAssessment(assessment, authentication)) {
                return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            }

            val allowed = scopedRequirements(assessment, authentication).mapNotNull { it.id }.toSet()
            val responses = responseRepository.findByRiskAssessmentIdAndEmail(id, email).filter { it.requirement.id in allowed }
            
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
    open fun getAssessmentAuthenticated(id: Long, authentication: Authentication): HttpResponse<*> {
        return try {
            log.debug("Fetching assessment for authenticated user: {}", id)

            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            if (!canAccessAssessment(assessment, authentication)) {
                return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            }

            // Get requirements for this assessment
            val requirements = scopedRequirements(assessment, authentication)

            // Get existing responses
            val requirementIds = requirements.mapNotNull { it.id }.toSet()
            val responses = responseRepository.findByRiskAssessmentId(assessment.id!!)
                .filter { it.requirement.id in requirementIds }

            // Calculate completion
            val completionPercentage = if (requirements.isNotEmpty()) {
                (responses.size * 100) / requirements.size
            } else {
                0
            }

            // Check permissions - can edit if assessor or respondent, can review if requestor or admin
            val canEdit = assessment.status == "STARTED" && canAnswerAssessment(assessment, authentication)
            val canReview = riskAssessmentAccessService.canReview(assessment, authentication)
            
            val assessmentData = AssessmentData(
                assessment = assessmentView(assessment),
                requirements = requirements,
                responses = responses,
                isComplete = responses.size >= requirements.size,
                completionPercentage = completionPercentage,
                canEdit = canEdit,
                canReview = canReview,
                acceptance = workflow.reviewDecision(assessment, authentication)
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
        return HttpResponse.ok(workflow.save(id, authentication, listOf(
            com.secman.service.AssessmentWorkflowService.Answer(request.requirementId, request.answerType, request.comment))).single())
    }

    @Post("/assessment/{id}/bulk-save")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun bulkSaveResponsesAuthenticated(id: Long, @Valid @Body request: BulkSaveResponseRequest, authentication: Authentication): HttpResponse<*> {
        val saved = workflow.save(id, authentication, request.responses.map {
            com.secman.service.AssessmentWorkflowService.Answer(it.requirementId, it.answerType, it.comment)
        })
        return HttpResponse.ok(mapOf("savedCount" to saved.size, "message" to "Responses saved successfully"))
    }

    @Get("/assessment/{id}/requirements-with-responses")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional(readOnly = true)
    open fun getRequirementsWithResponses(id: Long, authentication: Authentication): HttpResponse<*> {
        return try {
            log.debug("Fetching requirements with responses for assessment: {}", id)

            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            if (!canManageAssessment(assessment, authentication)) {
                return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            }

            val requirements = scopedRequirements(assessment, authentication)
            val allowed = scopedRequirements(assessment, authentication).mapNotNull { it.id }.toSet()
            val responses = responseRepository.findByRiskAssessmentId(id).filter { it.requirement.id in allowed }
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
    open fun createRiskFromAssessment(id: Long, @Valid @Body request: CreateRiskFromResponseRequest, authentication: Authentication): HttpResponse<*> {
        return try {
            log.debug("Creating risk from assessment: {} for requirement: {}", id, request.requirementId)

            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            if (!canManageAssessment(assessment, authentication)) {
                return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Assessment not found"))
            }

            val requirement = scopedRequirements(assessment, authentication)
                .firstOrNull { it.id == request.requirementId }
                ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Requirement is not part of this assessment"))
            
            // Get the response for this requirement
            val response = responseRepository.findByRiskAssessmentIdAndRequirementId(id, request.requirementId)
            
            // Validate that the response is non-compliant
            if (response == null || response.answerType == AnswerType.YES) {
                return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", 
                    "Can only create risks for non-compliant or not applicable requirements"))
            }
            
            // Determine the asset for the risk
            val asset = assessment.getAssociatedAsset()
            
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
            
            // Task authority does not expose the linked asset or user entity graph.
            HttpResponse.status<Map<String, Any>>(HttpStatus.CREATED).body(mapOf(
                "id" to savedRisk.id!!, "name" to savedRisk.name, "status" to savedRisk.status))
        } catch (e: Exception) {
            log.error("Error creating risk from assessment", e)
            HttpResponse.serverError<Any>()
        }
    }

    /**
     * Get the requirements that make up an assessment's questionnaire.
     *
     * 1. Release-pinned assessments (`lockedRelease` set — every assessment
     *    auto-started for a new AWS account) are answered against the frozen
     *    snapshots of that release, scoped by the assessment's use case tags. This
     *    is what keeps the questionnaire stable while requirements are re-imported.
     * 2. Unpinned assessments keep the previous behaviour: requirements tagged with
     *    the assessment's use cases, else every requirement.
     */
    private fun assessmentView(assessment: RiskAssessment) = assessment.taskView()
}
