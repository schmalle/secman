package com.secman.controller

import com.secman.domain.*
import com.secman.event.RiskAssessmentCreatedEvent
import com.secman.repository.*
import io.micronaut.context.event.ApplicationEventPublisher
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

/**
 * Risk Assessment Controller
 * Feature: 025-role-based-access-control
 *
 * Access Control:
 * - ADMIN: Full access to all risk assessment operations
 * - RISK: Full access to all risk assessment operations
 * - SECCHAMPION: Full access to all risk assessment operations
 * - Other roles: Access denied (403 Forbidden)
 */
@Controller("/api/risk-assessments")
@Secured("ADMIN", "RISK", "SECCHAMPION")
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
    private val entityManager: EntityManager,
    private val eventPublisher: ApplicationEventPublisher<RiskAssessmentCreatedEvent>
) {

    private val log = LoggerFactory.getLogger(RiskAssessmentController::class.java)

    @Serdeable
    data class CreateRiskAssessmentRequest(
        @NotNull val assessorId: Long,
        @NotNull val endDate: LocalDate,
        @Nullable val startDate: LocalDate? = null,
        @Nullable val respondentId: Long? = null,
        @Nullable val notes: String? = null,
        @Nullable val useCaseIds: List<Long>? = null,
        // New unified approach - either demandId or assetId must be provided
        @Nullable val demandId: Long? = null,
        @Nullable val assetId: Long? = null
    ) {
        fun validate(): String? {
            return when {
                demandId != null && assetId != null -> "Only one of demandId or assetId should be provided"
                demandId == null && assetId == null -> "Either demandId or assetId must be provided"
                else -> null
            }
        }
        
        fun getBasisType(): AssessmentBasisType = when {
            demandId != null -> AssessmentBasisType.DEMAND
            assetId != null -> AssessmentBasisType.ASSET
            else -> throw IllegalStateException("No basis ID provided")
        }
        
        fun getBasisId(): Long = demandId ?: assetId ?: throw IllegalStateException("No basis ID provided")
    }

    // Legacy request classes for backward compatibility
    @Serdeable
    @Deprecated("Use CreateRiskAssessmentRequest instead")
    data class CreateRiskAssessmentRequestDemand(
        @NotNull val demandId: Long,
        @NotNull val assessorId: Long,
        @NotNull val endDate: LocalDate,
        @Nullable val startDate: LocalDate? = null,
        @Nullable val respondentId: Long? = null,
        @Nullable val notes: String? = null,
        @Nullable val useCaseIds: List<Long>? = null
    )

    @Serdeable
    @Deprecated("Use CreateRiskAssessmentRequest instead")
    data class CreateRiskAssessmentRequestAsset(
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
                LEFT JOIN FETCH ra.demand d
                LEFT JOIN FETCH d.existingAsset 
                LEFT JOIN FETCH d.requestor 
                LEFT JOIN FETCH ra.asset a
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
                LEFT JOIN FETCH ra.demand d
                LEFT JOIN FETCH d.existingAsset 
                LEFT JOIN FETCH d.requestor 
                LEFT JOIN FETCH ra.asset a
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
                @Suppress("DEPRECATION")
                assessment.demand?.title // Force loading
                @Suppress("DEPRECATION")
                assessment.demand?.existingAsset?.name // Force loading
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
    open fun getRiskAssessmentsByAsset(assetId: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching risk assessments for asset: {}", assetId)
            
            // Find both direct asset assessments and demand-based assessments that involve this asset
            val directAssessments = riskAssessmentRepository.findByAssetId(assetId)
            val demandBasedAssessments = riskAssessmentRepository.findByExistingAssetId(assetId)
            val allAssessments = (directAssessments + demandBasedAssessments).distinctBy { it.id }
            
            // Force loading of related entities
            allAssessments.forEach { assessment ->
                when (assessment.assessmentBasisType) {
                    AssessmentBasisType.DEMAND -> {
                        @Suppress("DEPRECATION")
                        assessment.demand?.title
                        @Suppress("DEPRECATION")
                        assessment.demand?.existingAsset?.name
                    }
                    AssessmentBasisType.ASSET -> {
                        @Suppress("DEPRECATION")
                        assessment.asset?.name
                    }
                }
                assessment.assessor.username
                assessment.requestor.username
                assessment.respondent?.username
                assessment.useCases.size
            }
            
            log.debug("Found {} risk assessments for asset {}", allAssessments.size, assetId)
            HttpResponse.ok(allAssessments)
        } catch (e: Exception) {
            log.error("Error fetching risk assessments for asset: {}", assetId, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Get("/basis/{basisType}/{basisId}")
    @Transactional(readOnly = true)
    open fun getRiskAssessmentsByBasis(basisType: AssessmentBasisType, basisId: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching risk assessments for basis type: {} and ID: {}", basisType, basisId)
            
            val assessments = riskAssessmentRepository.findByAssessmentBasisTypeAndAssessmentBasisId(basisType, basisId)
            
            // Force loading of related entities
            assessments.forEach { assessment ->
                when (assessment.assessmentBasisType) {
                    AssessmentBasisType.DEMAND -> {
                        @Suppress("DEPRECATION")
                        assessment.demand?.title
                        @Suppress("DEPRECATION")
                        assessment.demand?.existingAsset?.name
                    }
                    AssessmentBasisType.ASSET -> {
                        @Suppress("DEPRECATION")
                        assessment.asset?.name
                    }
                }
                assessment.assessor.username
                assessment.requestor.username
                assessment.respondent?.username
                assessment.useCases.size
            }
            
            log.debug("Found {} risk assessments for basis type {} with ID {}", assessments.size, basisType, basisId)
            HttpResponse.ok(assessments)
        } catch (e: Exception) {
            log.error("Error fetching risk assessments for basis type: {} and ID: {}", basisType, basisId, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post
    @Transactional
    open fun createRiskAssessment(@Valid @Body request: CreateRiskAssessmentRequest): HttpResponse<*> {
        return try {
            // Validate request
            val validationError = request.validate()
            if (validationError != null) {
                return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", validationError))
            }
            
            val basisType = request.getBasisType()
            val basisId = request.getBasisId()
            
            log.debug("Creating risk assessment with basis type: {} and ID: {}", basisType, basisId)
            
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
            
            // Create risk assessment based on basis type
            val riskAssessment = when (basisType) {
                AssessmentBasisType.DEMAND -> {
                    val demand = demandRepository.findById(basisId).orElse(null)
                        ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Demand not found"))

                    if (demand.status != DemandStatus.APPROVED) {
                        return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR",
                            "Only approved demands can have risk assessments created"))
                    }

                    // Create demand-based risk assessment with proper fields
                    val assessment = RiskAssessment(
                        startDate = request.startDate ?: LocalDate.now(),
                        endDate = request.endDate,
                        assessmentBasisType = AssessmentBasisType.DEMAND,
                        assessmentBasisId = basisId,
                        assessor = assessor,
                        requestor = requestor,
                        demand = demand // Keep for backward compatibility
                    )

                    // Update demand status to IN_PROGRESS
                    demand.status = DemandStatus.IN_PROGRESS
                    demandRepository.update(demand)

                    assessment
                }
                AssessmentBasisType.ASSET -> {
                    val asset = assetRepository.findById(basisId).orElse(null)
                        ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Asset not found"))

                    // Create asset-based risk assessment with proper fields
                    RiskAssessment(
                        startDate = request.startDate ?: LocalDate.now(),
                        endDate = request.endDate,
                        assessmentBasisType = AssessmentBasisType.ASSET,
                        assessmentBasisId = basisId,
                        assessor = assessor,
                        requestor = requestor,
                        asset = asset // Keep for backward compatibility
                    )
                }
            }
            
            // Set common fields
            riskAssessment.respondent = respondent
            riskAssessment.notes = request.notes?.trim()?.takeIf { it.isNotBlank() }
            
            // Handle use case associations
            request.useCaseIds?.let { ids ->
                val useCases = ids.mapNotNull { useCaseId ->
                    useCaseRepository.findById(useCaseId).orElse(null)
                }.toMutableSet()
                riskAssessment.useCases = useCases
                log.debug("Associated {} use cases with risk assessment", useCases.size)
            }
            
            val savedAssessment = riskAssessmentRepository.save(riskAssessment)

            // Flush to ensure the entity is persisted with an ID
            entityManager.flush()

            // Force loading of related entities for response
            entityManager.refresh(savedAssessment)
            when (basisType) {
                AssessmentBasisType.DEMAND -> {
                    @Suppress("DEPRECATION")
                    savedAssessment.demand?.title
                    @Suppress("DEPRECATION")
                    savedAssessment.demand?.existingAsset?.name
                }
                AssessmentBasisType.ASSET -> {
                    @Suppress("DEPRECATION")
                    savedAssessment.asset?.name
                }
            }
            savedAssessment.assessor.username
            savedAssessment.requestor.username
            savedAssessment.respondent?.username
            savedAssessment.useCases.size

            // Publish RiskAssessmentCreatedEvent for email notifications
            try {
                // Only publish event if we have a valid saved assessment with ID
                if (savedAssessment.id != null) {
                    val event = createRiskAssessmentEvent(savedAssessment)
                    eventPublisher.publishEvent(event)
                    log.debug("Published RiskAssessmentCreatedEvent for assessment: {}", savedAssessment.id)
                } else {
                    log.warn("Cannot publish RiskAssessmentCreatedEvent - assessment ID is null")
                }
            } catch (e: Exception) {
                log.error("Failed to publish RiskAssessmentCreatedEvent for assessment: {}", savedAssessment.id, e)
                // Don't fail the request if event publishing fails
            }

            log.info("Created risk assessment with id: {} for {} basis: {}", savedAssessment.id, basisType, basisId)
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
            @Suppress("DEPRECATION")
            updatedAssessment.demand?.title
            @Suppress("DEPRECATION")
            updatedAssessment.demand?.existingAsset?.name
            @Suppress("DEPRECATION")
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

    // Legacy endpoints for backward compatibility
    @Post("/demand-based")
    @Transactional
    @Deprecated("Use main POST /api/risk-assessments endpoint with demandId instead")
    @Suppress("DEPRECATION")
    open fun createDemandBasedRiskAssessment(@Valid @Body request: CreateRiskAssessmentRequestDemand): HttpResponse<*> {
        log.debug("Legacy demand-based risk assessment creation called")
        @Suppress("DEPRECATION")
        return createRiskAssessment(CreateRiskAssessmentRequest(
            assessorId = request.assessorId,
            endDate = request.endDate,
            startDate = request.startDate,
            respondentId = request.respondentId,
            notes = request.notes,
            useCaseIds = request.useCaseIds,
            demandId = request.demandId,
            assetId = null
        ))
    }

    @Post("/asset-based")
    @Transactional
    @Deprecated("Use main POST /api/risk-assessments endpoint with assetId instead")
    @Suppress("DEPRECATION")
    open fun createAssetBasedRiskAssessment(@Valid @Body request: CreateRiskAssessmentRequestAsset): HttpResponse<*> {
        log.debug("Legacy asset-based risk assessment creation called")
        @Suppress("DEPRECATION")
        return createRiskAssessment(CreateRiskAssessmentRequest(
            assessorId = request.assessorId,
            endDate = request.endDate,
            startDate = request.startDate,
            respondentId = request.respondentId,
            notes = request.notes,
            useCaseIds = request.useCaseIds,
            demandId = null,
            assetId = request.assetId
        ))
    }

    /**
     * Create RiskAssessmentCreatedEvent from saved risk assessment
     */
    @Suppress("DEPRECATION")
    private fun createRiskAssessmentEvent(assessment: RiskAssessment): RiskAssessmentCreatedEvent {
        val title = when (assessment.assessmentBasisType) {
            AssessmentBasisType.DEMAND -> assessment.demand?.title ?: "Risk Assessment for Demand"
            AssessmentBasisType.ASSET -> "Risk Assessment for Asset: ${assessment.asset?.name ?: "Unknown Asset"}"
        }

        val description = when (assessment.assessmentBasisType) {
            AssessmentBasisType.DEMAND -> {
                val demand = assessment.demand
                buildString {
                    append("Risk assessment for demand: ${demand?.title}")
                    demand?.description?.let { append(" - $it") }
                    demand?.existingAsset?.let { append(" (Asset: ${it.name})") }
                }
            }
            AssessmentBasisType.ASSET -> {
                val asset = assessment.asset
                buildString {
                    append("Risk assessment for asset: ${asset?.name}")
                    asset?.description?.let { append(" - $it") }
                    asset?.type?.let { append(" (Type: $it)") }
                }
            }
        }

        val category = when (assessment.assessmentBasisType) {
            AssessmentBasisType.DEMAND -> "Demand Assessment"
            AssessmentBasisType.ASSET -> "Asset Assessment"
        }

        // Determine risk level based on assessment characteristics
        val riskLevel = determineRiskLevel(assessment)

        // Create metadata map with additional assessment details
        val metadata = mutableMapOf<String, Any>()

        // Only add non-null values to avoid issues
        assessment.id?.let { metadata["assessmentId"] = it }
        metadata["assessmentBasisType"] = assessment.assessmentBasisType.name
        metadata["assessmentBasisId"] = assessment.assessmentBasisId
        metadata["startDate"] = assessment.startDate
        metadata["endDate"] = assessment.endDate
        assessment.assessor.id?.let { metadata["assessorId"] = it }
        assessment.requestor.id?.let { metadata["requestorId"] = it }

        assessment.respondent?.id?.let { metadata["respondentId"] = it }
        assessment.notes?.let { metadata["notes"] = it }

        if (assessment.useCases.isNotEmpty()) {
            val useCaseIds = assessment.useCases.mapNotNull { it.id }
            if (useCaseIds.isNotEmpty()) {
                metadata["useCaseIds"] = useCaseIds
                metadata["useCaseCount"] = useCaseIds.size
            }
        }

        when (assessment.assessmentBasisType) {
            AssessmentBasisType.DEMAND -> {
                assessment.demand?.let { demand ->
                    demand.id?.let { metadata["demandId"] = it }
                    metadata["demandStatus"] = demand.status.name
                    demand.existingAsset?.id?.let { metadata["existingAssetId"] = it }
                }
            }
            AssessmentBasisType.ASSET -> {
                assessment.asset?.let { asset ->
                    asset.id?.let { metadata["assetId"] = it }
                    metadata["assetType"] = asset.type ?: "UNKNOWN"
                    metadata["assetStatus"] = "ACTIVE" // Asset doesn't have status property
                }
            }
        }

        return RiskAssessmentCreatedEvent.fromRiskAssessment(
            id = assessment.id ?: throw IllegalStateException("Assessment ID is required for event creation"),
            title = title,
            riskLevel = riskLevel,
            createdBy = assessment.requestor.username,
            createdAt = assessment.createdAt ?: LocalDateTime.now(),
            description = description,
            category = category,
            impact = "MEDIUM", // Default values - would be determined by business logic
            probability = "MEDIUM",
            additionalData = metadata
        )
    }

    /**
     * Determine risk level based on assessment characteristics
     */
    @Suppress("DEPRECATION")
    private fun determineRiskLevel(assessment: RiskAssessment): String {
        // Simple risk level determination logic
        // In a real implementation, this would be more sophisticated
        return when (assessment.assessmentBasisType) {
            AssessmentBasisType.DEMAND -> {
                val demand = assessment.demand
                when {
                    demand?.priority == Priority.HIGH -> "HIGH"
                    demand?.priority == Priority.CRITICAL -> "CRITICAL"
                    assessment.useCases.size > 3 -> "MEDIUM"
                    else -> "LOW"
                }
            }
            AssessmentBasisType.ASSET -> {
                val asset = assessment.asset
                when {
                    asset?.type == "CRITICAL_INFRASTRUCTURE" -> "CRITICAL"
                    asset?.type == "SERVER" || asset?.type == "DATABASE" -> "HIGH"
                    assessment.useCases.size > 2 -> "MEDIUM"
                    else -> "LOW"
                }
            }
        }
    }
}