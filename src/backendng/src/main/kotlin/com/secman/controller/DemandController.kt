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
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

@Controller("/api/demands")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class DemandController(
    private val demandRepository: DemandRepository,
    private val assetRepository: AssetRepository,
    private val userRepository: UserRepository,
    private val entityManager: EntityManager
) {
    
    private val log = LoggerFactory.getLogger(DemandController::class.java)

    @Serdeable
    data class CreateDemandRequest(
        @NotBlank @Size(max = 255) val title: String,
        @Nullable val description: String? = null,
        @NotNull val demandType: DemandType,
        @Nullable val existingAssetId: Long? = null,
        @Nullable @Size(max = 255) val newAssetName: String? = null,
        @Nullable @Size(max = 100) val newAssetType: String? = null,
        @Nullable @Size(max = 45) val newAssetIp: String? = null,
        @Nullable @Size(max = 255) val newAssetOwner: String? = null,
        @Nullable val newAssetDescription: String? = null,
        @Nullable val businessJustification: String? = null,
        @Nullable val priority: Priority? = Priority.MEDIUM,
        @NotNull val requestorId: Long
    )

    @Serdeable
    data class UpdateDemandRequest(
        @Nullable @Size(max = 255) val title: String? = null,
        @Nullable val description: String? = null,
        @Nullable val existingAssetId: Long? = null,
        @Nullable @Size(max = 255) val newAssetName: String? = null,
        @Nullable @Size(max = 100) val newAssetType: String? = null,
        @Nullable @Size(max = 45) val newAssetIp: String? = null,
        @Nullable @Size(max = 255) val newAssetOwner: String? = null,
        @Nullable val newAssetDescription: String? = null,
        @Nullable val businessJustification: String? = null,
        @Nullable val priority: Priority? = null,
        @Nullable val status: DemandStatus? = null
    )

    @Serdeable
    data class ApproveDemandRequest(
        @NotNull val approved: Boolean,
        @Nullable val rejectionReason: String? = null
    )

    @Serdeable
    data class DemandFilterRequest(
        @Nullable val status: DemandStatus? = null,
        @Nullable val demandType: DemandType? = null,
        @Nullable val priority: Priority? = null,
        @Nullable val requestorId: Long? = null
    )

    @Serdeable
    data class ErrorResponse(
        val error: String,
        val message: String
    )

    @Serdeable
    data class DemandSummary(
        val totalDemands: Long,
        val pendingDemands: Long,
        val approvedDemands: Long,
        val rejectedDemands: Long,
        val changeDemands: Long,
        val createNewDemands: Long
    )

    @Get
    @Transactional(readOnly = true)
    open fun listDemands(@QueryValue @Nullable status: DemandStatus?,
                        @QueryValue @Nullable demandType: DemandType?,
                        @QueryValue @Nullable priority: Priority?,
                        @QueryValue @Nullable requestorId: Long?): HttpResponse<List<Demand>> {
        return try {
            log.debug("Fetching demands with filters - status: {}, type: {}, priority: {}, requestor: {}", 
                     status, demandType, priority, requestorId)
            
            val demands = if (status != null || demandType != null || priority != null || requestorId != null) {
                demandRepository.findWithFilters(status, demandType, priority, requestorId)
            } else {
                entityManager.createQuery(
                    """
                    SELECT DISTINCT d FROM Demand d 
                    LEFT JOIN FETCH d.requestor 
                    LEFT JOIN FETCH d.approver 
                    LEFT JOIN FETCH d.existingAsset 
                    ORDER BY d.requestedDate DESC
                    """,
                    Demand::class.java
                ).resultList
            }
            
            log.debug("Found {} demands", demands.size)
            HttpResponse.ok(demands)
        } catch (e: Exception) {
            log.error("Error fetching demands", e)
            HttpResponse.serverError<List<Demand>>()
        }
    }

    @Get("/{id}")
    @Transactional(readOnly = true)
    open fun getDemand(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching demand with id: {}", id)
            
            val demand = entityManager.createQuery(
                """
                SELECT d FROM Demand d 
                LEFT JOIN FETCH d.requestor 
                LEFT JOIN FETCH d.approver 
                LEFT JOIN FETCH d.existingAsset 
                WHERE d.id = :id
                """,
                Demand::class.java
            ).setParameter("id", id).resultList.firstOrNull()
            
            if (demand != null) {
                log.debug("Found demand: {}", demand.id)
                HttpResponse.ok(demand)
            } else {
                log.debug("Demand not found with id: {}", id)
                HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Demand not found"))
            }
        } catch (e: Exception) {
            log.error("Error fetching demand with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Get("/summary")
    @Transactional(readOnly = true)
    open fun getDemandSummary(): HttpResponse<DemandSummary> {
        return try {
            log.debug("Generating demand summary")
            
            val summary = DemandSummary(
                totalDemands = demandRepository.count(),
                pendingDemands = demandRepository.countByStatus(DemandStatus.PENDING),
                approvedDemands = demandRepository.countByStatus(DemandStatus.APPROVED),
                rejectedDemands = demandRepository.countByStatus(DemandStatus.REJECTED),
                changeDemands = demandRepository.countByDemandType(DemandType.CHANGE),
                createNewDemands = demandRepository.countByDemandType(DemandType.CREATE_NEW)
            )
            
            log.debug("Generated demand summary: {}", summary)
            HttpResponse.ok(summary)
        } catch (e: Exception) {
            log.error("Error generating demand summary", e)
            HttpResponse.serverError<DemandSummary>()
        }
    }

    @Get("/approved/available")
    @Transactional(readOnly = true)
    open fun getApprovedDemandsForRiskAssessment(): HttpResponse<List<Demand>> {
        return try {
            log.debug("Fetching approved demands available for risk assessment")
            
            val demands = demandRepository.findApprovedDemandsWithoutRiskAssessment()
            
            // Force loading of related entities
            demands.forEach { demand ->
                demand.requestor.username
                demand.approver?.username
                demand.existingAsset?.name
            }
            
            log.debug("Found {} approved demands available for risk assessment", demands.size)
            HttpResponse.ok(demands)
        } catch (e: Exception) {
            log.error("Error fetching approved demands for risk assessment", e)
            HttpResponse.serverError<List<Demand>>()
        }
    }

    @Post
    @Transactional
    open fun createDemand(@Valid @Body request: CreateDemandRequest): HttpResponse<*> {
        return try {
            log.debug("Creating demand: {}", request.title)
            
            // Validate requestor exists
            val requestor = userRepository.findById(request.requestorId).orElse(null)
                ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Requestor not found"))
            
            // Validate asset information based on demand type
            val existingAsset = if (request.demandType == DemandType.CHANGE) {
                request.existingAssetId?.let { assetId ->
                    assetRepository.findById(assetId).orElse(null)
                        ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Existing asset not found"))
                } ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Existing asset ID required for CHANGE demand"))
            } else null
            
            // Validate new asset information for CREATE_NEW demands
            if (request.demandType == DemandType.CREATE_NEW) {
                if (request.newAssetName.isNullOrBlank() || 
                    request.newAssetType.isNullOrBlank() || 
                    request.newAssetOwner.isNullOrBlank()) {
                    return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", 
                        "New asset name, type, and owner are required for CREATE_NEW demand"))
                }
            }
            
            // Create demand
            val demand = Demand(
                title = request.title.trim(),
                description = request.description?.trim()?.takeIf { it.isNotBlank() },
                demandType = request.demandType,
                existingAsset = existingAsset,
                newAssetName = request.newAssetName?.trim()?.takeIf { it.isNotBlank() },
                newAssetType = request.newAssetType?.trim()?.takeIf { it.isNotBlank() },
                newAssetIp = request.newAssetIp?.trim()?.takeIf { it.isNotBlank() },
                newAssetOwner = request.newAssetOwner?.trim()?.takeIf { it.isNotBlank() },
                newAssetDescription = request.newAssetDescription?.trim()?.takeIf { it.isNotBlank() },
                businessJustification = request.businessJustification?.trim()?.takeIf { it.isNotBlank() },
                priority = request.priority ?: Priority.MEDIUM,
                requestor = requestor
            )
            
            val savedDemand = demandRepository.save(demand)
            
            // Fetch the complete demand with all relationships
            val completeDemand = entityManager.createQuery(
                """
                SELECT d FROM Demand d 
                LEFT JOIN FETCH d.requestor 
                LEFT JOIN FETCH d.existingAsset 
                WHERE d.id = :id
                """,
                Demand::class.java
            ).setParameter("id", savedDemand.id)
             .singleResult
            
            log.info("Created demand with id: {}", completeDemand.id)
            HttpResponse.status<Demand>(HttpStatus.CREATED).body(completeDemand)
        } catch (e: Exception) {
            log.error("Error creating demand", e)
            HttpResponse.serverError<Any>()
        }
    }

    @Put("/{id}")
    @Transactional
    open fun updateDemand(id: Long, @Valid @Body request: UpdateDemandRequest): HttpResponse<*> {
        return try {
            log.debug("Updating demand with id: {}", id)
            
            val demand = demandRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Demand not found"))
            
            // Update fields if provided
            request.title?.let { demand.title = it.trim() }
            request.description?.let { demand.description = it.trim().takeIf { it.isNotBlank() } }
            request.businessJustification?.let { demand.businessJustification = it.trim().takeIf { it.isNotBlank() } }
            request.priority?.let { demand.priority = it }
            request.status?.let { demand.status = it }
            
            // Update asset information based on demand type
            if (demand.demandType == DemandType.CHANGE) {
                request.existingAssetId?.let { assetId ->
                    val asset = assetRepository.findById(assetId).orElse(null)
                        ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Asset not found"))
                    demand.existingAsset = asset
                }
            } else if (demand.demandType == DemandType.CREATE_NEW) {
                request.newAssetName?.let { demand.newAssetName = it.trim().takeIf { it.isNotBlank() } }
                request.newAssetType?.let { demand.newAssetType = it.trim().takeIf { it.isNotBlank() } }
                request.newAssetIp?.let { demand.newAssetIp = it.trim().takeIf { it.isNotBlank() } }
                request.newAssetOwner?.let { demand.newAssetOwner = it.trim().takeIf { it.isNotBlank() } }
                request.newAssetDescription?.let { demand.newAssetDescription = it.trim().takeIf { it.isNotBlank() } }
            }
            
            val updatedDemand = demandRepository.update(demand)
            
            // Fetch the complete demand with all relationships
            val completeDemand = entityManager.createQuery(
                """
                SELECT d FROM Demand d 
                LEFT JOIN FETCH d.requestor 
                LEFT JOIN FETCH d.approver 
                LEFT JOIN FETCH d.existingAsset 
                WHERE d.id = :id
                """,
                Demand::class.java
            ).setParameter("id", updatedDemand.id)
             .singleResult
            
            log.info("Updated demand with id: {}", id)
            HttpResponse.ok(completeDemand)
        } catch (e: Exception) {
            log.error("Error updating demand with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post("/{id}/approve")
    @Transactional
    open fun approveDemand(id: Long, @Valid @Body request: ApproveDemandRequest): HttpResponse<*> {
        return try {
            log.debug("Processing approval for demand: {}", id)
            
            val demand = demandRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Demand not found"))
            
            if (demand.status != DemandStatus.PENDING) {
                return HttpResponse.badRequest(ErrorResponse("INVALID_STATE", 
                    "Only pending demands can be approved or rejected"))
            }
            
            // TODO: Get current user from security context
            // For now, we'll need to pass approver ID or get from context
            // This is a simplification - in production, you'd get the approver from the security context
            
            if (request.approved) {
                demand.status = DemandStatus.APPROVED
                demand.approvedDate = LocalDateTime.now()
                demand.rejectionReason = null
                log.info("Approved demand: {}", id)
            } else {
                demand.status = DemandStatus.REJECTED
                demand.rejectionReason = request.rejectionReason?.trim()?.takeIf { it.isNotBlank() }
                    ?: "No reason provided"
                log.info("Rejected demand: {} with reason: {}", id, demand.rejectionReason)
            }
            
            val updatedDemand = demandRepository.update(demand)
            
            // Fetch the complete demand with all relationships
            val completeDemand = entityManager.createQuery(
                """
                SELECT d FROM Demand d 
                LEFT JOIN FETCH d.requestor 
                LEFT JOIN FETCH d.approver 
                LEFT JOIN FETCH d.existingAsset 
                WHERE d.id = :id
                """,
                Demand::class.java
            ).setParameter("id", updatedDemand.id)
             .singleResult
            
            HttpResponse.ok(completeDemand)
        } catch (e: Exception) {
            log.error("Error processing approval for demand: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Delete("/{id}")
    @Transactional
    open fun deleteDemand(id: Long): HttpResponse<*> {
        return try {
            log.debug("Deleting demand with id: {}", id)
            
            val demand = demandRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Demand not found"))
            
            // Check if demand has associated risk assessments
            val hasRiskAssessments = entityManager.createQuery(
                "SELECT COUNT(ra) FROM RiskAssessment ra WHERE ra.demand.id = :demandId",
                Long::class.java
            ).setParameter("demandId", id).singleResult > 0
            
            if (hasRiskAssessments) {
                return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", 
                    "Cannot delete demand with associated risk assessments"))
            }
            
            demandRepository.delete(demand)
            
            log.info("Deleted demand with id: {}", id)
            HttpResponse.ok(mapOf("message" to "Demand deleted successfully"))
        } catch (e: Exception) {
            log.error("Error deleting demand with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }
}