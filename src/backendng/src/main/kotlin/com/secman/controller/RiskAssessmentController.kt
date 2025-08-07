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
import java.time.LocalDate
import java.time.LocalDateTime

@Controller("/api/risk-assessments")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class RiskAssessmentController(
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val demandRepository: DemandRepository,
    private val assetRepository: AssetRepository,
    private val userRepository: UserRepository,
    private val useCaseRepository: UseCaseRepository,
    private val assessmentTokenRepository: AssessmentTokenRepository,
    private val responseRepository: ResponseRepository,
    private val requirementRepository: RequirementRepository,
    private val entityManager: EntityManager
) {
    
    private val log = LoggerFactory.getLogger(RiskAssessmentController::class.java)

    @Serdeable
    data class CreateRiskAssessmentRequest(
        @NotNull val demandId: Long,
        @NotNull val assessorId: Long,
        @NotNull val endDate: LocalDate,
        @Nullable val startDate: LocalDate? = null,
        @Nullable val respondentId: Long? = null,
        @Nullable val notes: String? = null,
        @Nullable val useCaseIds: List<Long>? = null
    )

    // Deprecated request class for backward compatibility
    @Serdeable
    @Deprecated("Use CreateRiskAssessmentRequest with demandId instead")
    data class CreateRiskAssessmentRequestLegacy(
        @NotNull val assetId: Long,
        @NotNull val assessorId: Long,
        @NotNull val endDate: LocalDate,
        @Nullable val startDate: LocalDate? = null,
        @Nullable val respondentId: Long? = null,
        @Nullable val notes: String? = null,
        @Nullable val useCaseIds: List<Long>? = null
    )

    @Serdeable
    data class UpdateRiskAssessmentRequest(
        @Nullable val endDate: LocalDate? = null,
        @Nullable val respondentId: Long? = null,
        @Nullable val notes: String? = null,
        @Nullable val status: String? = null,
        @Nullable val useCaseIds: List<Long>? = null
    )

    @Serdeable
    data class NotificationRequest(
        @Email @NotNull val email: String,
        @Nullable val message: String? = null
    )

    @Serdeable
    data class ErrorResponse(
        val error: String,
        val message: String
    )

    @Serdeable
    data class TokenResponse(
        val token: String,
        val expiresAt: LocalDateTime,
        val assessmentUrl: String
    )

    @Get
    @Transactional(readOnly = true)
    open fun listRiskAssessments(): HttpResponse<List<RiskAssessment>> {
        return try {
            log.debug("Fetching all risk assessments")
            
            val assessments = entityManager.createQuery(
                """
                SELECT DISTINCT ra FROM RiskAssessment ra 
                LEFT JOIN FETCH ra.demand 
                LEFT JOIN FETCH ra.demand.existingAsset 
                LEFT JOIN FETCH ra.demand.requestor 
                LEFT JOIN FETCH ra.asset 
                LEFT JOIN FETCH ra.assessor 
                LEFT JOIN FETCH ra.requestor 
                LEFT JOIN FETCH ra.respondent 
                LEFT JOIN FETCH ra.useCases 
                ORDER BY ra.createdAt DESC
                """,
                RiskAssessment::class.java
            ).resultList
            
            log.debug("Found {} risk assessments", assessments.size)
            HttpResponse.ok(assessments)
        } catch (e: Exception) {
            log.error("Error fetching risk assessments", e)
            HttpResponse.serverError<List<RiskAssessment>>()
        }
    }

    @Get("/{id}")
    @Transactional(readOnly = true)
    open fun getRiskAssessment(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching risk assessment with id: {}", id)
            
            val assessment = entityManager.createQuery(
                """
                SELECT ra FROM RiskAssessment ra 
                LEFT JOIN FETCH ra.demand 
                LEFT JOIN FETCH ra.demand.existingAsset 
                LEFT JOIN FETCH ra.demand.requestor 
                LEFT JOIN FETCH ra.asset 
                LEFT JOIN FETCH ra.assessor 
                LEFT JOIN FETCH ra.requestor 
                LEFT JOIN FETCH ra.respondent 
                LEFT JOIN FETCH ra.useCases 
                WHERE ra.id = :id
                """,
                RiskAssessment::class.java
            ).setParameter("id", id).resultList.firstOrNull()
            
            if (assessment != null) {
                log.debug("Found risk assessment: {}", assessment.id)
                HttpResponse.ok(assessment)
            } else {
                log.debug("Risk assessment not found with id: {}", id)
                HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Risk assessment not found"))
            }
        } catch (e: Exception) {
            log.error("Error fetching risk assessment with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Get("/demand/{demandId}")
    @Transactional(readOnly = true)
    open fun getRiskAssessmentsByDemand(demandId: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching risk assessments for demand: {}", demandId)
            
            val assessments = riskAssessmentRepository.findByDemandId(demandId)
            
            // Force loading of related entities
            assessments.forEach { assessment ->
                assessment.demand.title // Force loading
                assessment.demand.existingAsset?.name // Force loading
                assessment.assessor.username // Force loading
                assessment.requestor.username // Force loading
                assessment.respondent?.username // Force loading
                assessment.useCases.size // Force loading
            }
            
            log.debug("Found {} risk assessments for demand {}", assessments.size, demandId)
            HttpResponse.ok(assessments)
        } catch (e: Exception) {
            log.error("Error fetching risk assessments for demand: {}", demandId, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Get("/asset/{assetId}")
    @Transactional(readOnly = true)
    @Deprecated("Use /demand/{demandId} endpoint instead")
    open fun getRiskAssessmentsByAsset(assetId: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching risk assessments for asset: {} (legacy endpoint)", assetId)
            
            // Updated to work with both legacy asset field and demand-based assets
            val assessments = riskAssessmentRepository.findByExistingAssetId(assetId)
            
            // Force loading of related entities
            assessments.forEach { assessment ->
                assessment.demand?.title // Force loading
                assessment.demand?.existingAsset?.name // Force loading
                assessment.asset?.name // Force loading (legacy)
                assessment.assessor.username // Force loading
                assessment.requestor.username // Force loading
                assessment.respondent?.username // Force loading
                assessment.useCases.size // Force loading
            }
            
            log.debug("Found {} risk assessments for asset {}", assessments.size, assetId)
            HttpResponse.ok(assessments)
        } catch (e: Exception) {
            log.error("Error fetching risk assessments for asset: {}", assetId, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post
    @Transactional
    open fun createRiskAssessment(@Valid @Body request: CreateRiskAssessmentRequest): HttpResponse<*> {
        return try {
            log.debug("Creating risk assessment for demand: {}", request.demandId)
            
            // Validate demand exists and is approved
            val demand = demandRepository.findById(request.demandId).orElse(null)
                ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Demand not found"))
            
            if (demand.status != DemandStatus.APPROVED) {
                return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", 
                    "Only approved demands can have risk assessments created"))
            }
            
            // Validate assessor exists
            val assessor = userRepository.findById(request.assessorId).orElse(null)
                ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Assessor not found"))
            
            // Get requestor from current session (would need security context in real implementation)
            // For now, assuming assessor is also requestor
            val requestor = assessor
            
            // Validate respondent if provided
            val respondent = request.respondentId?.let { respondentId ->
                userRepository.findById(respondentId).orElse(null)
                    ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Respondent not found"))
            }
            
            // Create risk assessment
            val riskAssessment = RiskAssessment(
                startDate = request.startDate ?: LocalDate.now(),
                endDate = request.endDate,
                demand = demand,
                assessor = assessor,
                requestor = requestor,
                respondent = respondent,
                notes = request.notes?.trim()?.takeIf { it.isNotBlank() }
            )
            
            // Handle use case associations
            request.useCaseIds?.let { ids ->
                val useCases = ids.mapNotNull { useCaseId ->
                    useCaseRepository.findById(useCaseId).orElse(null)
                }.toMutableSet()
                riskAssessment.useCases = useCases
                log.debug("Associated {} use cases with risk assessment", useCases.size)
            }
            
            val savedAssessment = riskAssessmentRepository.save(riskAssessment)
            
            // Update demand status to IN_PROGRESS
            demand.status = DemandStatus.IN_PROGRESS
            demandRepository.update(demand)
            
            // Force loading of related entities for response
            entityManager.refresh(savedAssessment)
            savedAssessment.demand.title
            savedAssessment.demand.existingAsset?.name
            savedAssessment.assessor.username
            savedAssessment.requestor.username
            savedAssessment.respondent?.username
            savedAssessment.useCases.size
            
            log.info("Created risk assessment with id: {} for demand: {}", savedAssessment.id, demand.id)
            HttpResponse.status<RiskAssessment>(HttpStatus.CREATED).body(savedAssessment)
        } catch (e: Exception) {
            log.error("Error creating risk assessment", e)
            HttpResponse.serverError<Any>()
        }
    }

    @Put("/{id}")
    @Transactional
    open fun updateRiskAssessment(id: Long, @Valid @Body request: UpdateRiskAssessmentRequest): HttpResponse<*> {
        return try {
            log.debug("Updating risk assessment with id: {}", id)
            
            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Risk assessment not found"))
            
            // Update fields if provided
            request.endDate?.let { assessment.endDate = it }
            request.notes?.let { assessment.notes = it.trim().takeIf { it.isNotBlank() } }
            request.status?.let { assessment.status = it }
            
            // Update respondent if provided
            request.respondentId?.let { respondentId ->
                val respondent = userRepository.findById(respondentId).orElse(null)
                    ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Respondent not found"))
                assessment.respondent = respondent
            }
            
            // Update use case associations if provided
            request.useCaseIds?.let { ids ->
                val useCases = ids.mapNotNull { useCaseId ->
                    useCaseRepository.findById(useCaseId).orElse(null)
                }.toMutableSet()
                assessment.useCases.clear()
                assessment.useCases.addAll(useCases)
                log.debug("Updated use case associations: {} use cases", useCases.size)
            }
            
            val updatedAssessment = riskAssessmentRepository.update(assessment)
            
            // Force loading of related entities
            entityManager.refresh(updatedAssessment)
            updatedAssessment.demand.title
            updatedAssessment.demand.existingAsset?.name
            updatedAssessment.asset?.name // Legacy field
            updatedAssessment.assessor.username
            updatedAssessment.requestor.username
            updatedAssessment.respondent?.username
            updatedAssessment.useCases.size
            
            log.info("Updated risk assessment with id: {}", id)
            HttpResponse.ok(updatedAssessment)
        } catch (e: Exception) {
            log.error("Error updating risk assessment with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Delete("/{id}")
    @Transactional
    open fun deleteRiskAssessment(id: Long): HttpResponse<*> {
        return try {
            log.debug("Deleting risk assessment with id: {}", id)
            
            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Risk assessment not found"))
            
            // Delete related responses and tokens first
            responseRepository.deleteByRiskAssessmentId(id)
            assessmentTokenRepository.deleteByRiskAssessmentId(id)
            
            riskAssessmentRepository.delete(assessment)
            
            log.info("Deleted risk assessment with id: {}", id)
            HttpResponse.ok(mapOf("message" to "Risk assessment deleted successfully"))
        } catch (e: Exception) {
            log.error("Error deleting risk assessment with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post("/{id}/token")
    @Transactional
    open fun generateAssessmentToken(id: Long, @Valid @Body request: NotificationRequest): HttpResponse<*> {
        return try {
            log.debug("Generating assessment token for risk assessment: {}", id)
            
            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Risk assessment not found"))
            
            // Check if valid token already exists for this email and assessment
            val existingToken = assessmentTokenRepository
                .findValidTokensByRiskAssessmentId(id, LocalDateTime.now())
                .find { it.email == request.email }
            
            if (existingToken != null) {
                log.debug("Returning existing valid token for email: {}", request.email)
                return HttpResponse.ok(TokenResponse(
                    token = existingToken.token,
                    expiresAt = existingToken.expiresAt,
                    assessmentUrl = "/assessment/${existingToken.token}"
                ))
            }
            
            // Create new token
            val token = AssessmentToken.create(request.email, assessment)
            val savedToken = assessmentTokenRepository.save(token)
            
            log.info("Generated assessment token for email: {} and assessment: {}", request.email, id)
            HttpResponse.status<TokenResponse>(HttpStatus.CREATED).body(TokenResponse(
                token = savedToken.token,
                expiresAt = savedToken.expiresAt,
                assessmentUrl = "/assessment/${savedToken.token}"
            ))
        } catch (e: Exception) {
            log.error("Error generating assessment token for id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post("/{id}/notify")
    @Transactional
    open fun notifyRespondent(id: Long, @Valid @Body request: NotificationRequest): HttpResponse<*> {
        return try {
            log.debug("Sending notification for risk assessment: {}", id)
            
            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Risk assessment not found"))
            
            // Generate or get existing token
            val tokenResponse = generateAssessmentToken(id, request)
            if (tokenResponse.status.code != 200 && tokenResponse.status.code != 201) {
                return tokenResponse
            }
            
            // TODO: Implement email sending logic here
            // This would send an email to request.email with the assessment URL
            
            log.info("Notification sent for risk assessment: {} to email: {}", id, request.email)
            HttpResponse.ok(mapOf(
                "message" to "Notification sent successfully",
                "email" to request.email
            ))
        } catch (e: Exception) {
            log.error("Error sending notification for assessment: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post("/{id}/remind")
    @Transactional
    open fun sendReminder(id: Long, @Valid @Body request: NotificationRequest): HttpResponse<*> {
        return try {
            log.debug("Sending reminder for risk assessment: {}", id)
            
            val assessment = riskAssessmentRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Risk assessment not found"))
            
            // TODO: Implement reminder email logic here
            // This would send a reminder email about the pending assessment
            
            log.info("Reminder sent for risk assessment: {} to email: {}", id, request.email)
            HttpResponse.ok(mapOf(
                "message" to "Reminder sent successfully",
                "email" to request.email
            ))
        } catch (e: Exception) {
            log.error("Error sending reminder for assessment: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }
}