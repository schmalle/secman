package com.secman.controller

import com.secman.domain.Risk
import com.secman.repository.AssetRepository
import com.secman.repository.RiskRepository
import com.secman.repository.UserRepository
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
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import java.time.LocalDate

@Controller("/api/risks")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class RiskController(
    private val riskRepository: RiskRepository,
    private val assetRepository: AssetRepository,
    private val userRepository: UserRepository,
    private val entityManager: EntityManager
) {
    
    private val log = LoggerFactory.getLogger(RiskController::class.java)

    @Serdeable
    data class CreateRiskRequest(
        @NotBlank val name: String,
        @Nullable val description: String? = null,
        @Min(1) @Max(5) @Nullable val likelihood: Int? = 1,
        @Min(1) @Max(5) @Nullable val impact: Int? = 1,
        @Nullable val status: String? = "OPEN",
        @Nullable val severity: String? = null,
        @Nullable val deadline: LocalDate? = null,
        @Nullable val ownerId: Long? = null,
        @Nullable val assetId: Long? = null
    )

    @Serdeable
    data class UpdateRiskRequest(
        @Nullable val name: String? = null,
        @Nullable val description: String? = null,
        @Min(1) @Max(5) @Nullable val likelihood: Int? = null,
        @Min(1) @Max(5) @Nullable val impact: Int? = null,
        @Nullable val status: String? = null,
        @Nullable val severity: String? = null,
        @Nullable val deadline: LocalDate? = null,
        @Nullable val ownerId: Long? = null,
        @Nullable val assetId: Long? = null
    )

    @Serdeable
    data class ErrorResponse(
        val error: String,
        val message: String
    )

    @Get
    @Transactional(readOnly = true)
    open fun listRisks(): HttpResponse<List<Risk>> {
        return try {
            log.debug("Fetching all risks")
            
            val risks = entityManager.createQuery(
                """
                SELECT DISTINCT r FROM Risk r 
                LEFT JOIN FETCH r.owner 
                LEFT JOIN FETCH r.asset 
                ORDER BY r.riskLevel DESC, r.createdAt DESC
                """,
                Risk::class.java
            ).resultList
            
            log.debug("Found {} risks", risks.size)
            HttpResponse.ok(risks)
        } catch (e: Exception) {
            log.error("Error fetching risks", e)
            HttpResponse.serverError<List<Risk>>()
        }
    }

    @Get("/{id}")
    @Transactional(readOnly = true)
    open fun getRisk(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching risk with id: {}", id)
            
            val risk = entityManager.createQuery(
                """
                SELECT r FROM Risk r 
                LEFT JOIN FETCH r.owner 
                LEFT JOIN FETCH r.asset 
                WHERE r.id = :id
                """,
                Risk::class.java
            ).setParameter("id", id).resultList.firstOrNull()
            
            if (risk != null) {
                log.debug("Found risk: {}", risk.name)
                HttpResponse.ok(risk)
            } else {
                log.debug("Risk not found with id: {}", id)
                HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Risk not found"))
            }
        } catch (e: Exception) {
            log.error("Error fetching risk with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Get("/asset/{assetId}")
    @Transactional(readOnly = true)
    open fun getRisksByAsset(assetId: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching risks for asset: {}", assetId)
            
            val risks = riskRepository.findByAssetId(assetId)
            
            // Force loading of related entities
            risks.forEach { risk ->
                risk.owner?.username // Force loading
                risk.asset?.name // Force loading
            }
            
            log.debug("Found {} risks for asset {}", risks.size, assetId)
            HttpResponse.ok(risks)
        } catch (e: Exception) {
            log.error("Error fetching risks for asset: {}", assetId, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post
    @Transactional
    open fun createRisk(@Valid @Body request: CreateRiskRequest): HttpResponse<*> {
        return try {
            log.debug("Creating risk with name: {}", request.name)
            
            val trimmedName = request.name.trim()
            
            if (trimmedName.isBlank()) {
                return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Name cannot be empty"))
            }
            
            // Validate owner if provided
            val owner = request.ownerId?.let { ownerId ->
                userRepository.findById(ownerId).orElse(null)
                    ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Owner not found"))
            }
            
            // Validate asset if provided
            val asset = request.assetId?.let { assetId ->
                assetRepository.findById(assetId).orElse(null)
                    ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Asset not found"))
            }
            
            // Create risk
            val risk = Risk(
                name = trimmedName,
                description = request.description?.trim()?.takeIf { it.isNotBlank() },
                likelihood = request.likelihood ?: 1,
                impact = request.impact ?: 1,
                status = request.status ?: "OPEN",
                severity = request.severity?.trim()?.takeIf { it.isNotBlank() },
                deadline = request.deadline,
                owner = owner,
                asset = asset
            )
            
            val savedRisk = riskRepository.save(risk)
            
            // Force loading of related entities for response
            entityManager.refresh(savedRisk)
            savedRisk.owner?.username
            savedRisk.asset?.name
            
            log.info("Created risk: {} with id: {} and risk level: {}", 
                savedRisk.name, savedRisk.id, savedRisk.getRiskLevelText())
            HttpResponse.status<Risk>(HttpStatus.CREATED).body(savedRisk)
        } catch (e: Exception) {
            log.error("Error creating risk", e)
            HttpResponse.serverError<Any>()
        }
    }

    @Put("/{id}")
    @Transactional
    open fun updateRisk(id: Long, @Valid @Body request: UpdateRiskRequest): HttpResponse<*> {
        return try {
            log.debug("Updating risk with id: {}", id)
            
            val risk = riskRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Risk not found"))
            
            // Update name if provided
            request.name?.let { newName ->
                val trimmedName = newName.trim()
                if (trimmedName.isBlank()) {
                    return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Name cannot be empty"))
                }
                risk.name = trimmedName
            }
            
            // Update other fields if provided
            request.description?.let { risk.description = it.trim().takeIf { it.isNotBlank() } }
            request.likelihood?.let { risk.likelihood = it }
            request.impact?.let { risk.impact = it }
            request.status?.let { risk.status = it }
            request.severity?.let { risk.severity = it.trim().takeIf { it.isNotBlank() } }
            request.deadline?.let { risk.deadline = it }
            
            // Update owner if provided
            request.ownerId?.let { ownerId ->
                val owner = userRepository.findById(ownerId).orElse(null)
                    ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Owner not found"))
                risk.owner = owner
            }
            
            // Update asset if provided
            request.assetId?.let { assetId ->
                val asset = assetRepository.findById(assetId).orElse(null)
                    ?: return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Asset not found"))
                risk.asset = asset
            }
            
            val updatedRisk = riskRepository.update(risk)
            
            // Force loading of related entities
            entityManager.refresh(updatedRisk)
            updatedRisk.owner?.username
            updatedRisk.asset?.name
            
            log.info("Updated risk: {} with id: {} and risk level: {}", 
                updatedRisk.name, updatedRisk.id, updatedRisk.getRiskLevelText())
            HttpResponse.ok(updatedRisk)
        } catch (e: Exception) {
            log.error("Error updating risk with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Delete("/{id}")
    @Transactional
    open fun deleteRisk(id: Long): HttpResponse<*> {
        return try {
            log.debug("Deleting risk with id: {}", id)
            
            val risk = riskRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Risk not found"))
            
            riskRepository.delete(risk)
            
            log.info("Deleted risk: {} with id: {}", risk.name, id)
            HttpResponse.ok(mapOf("message" to "Risk deleted successfully"))
        } catch (e: Exception) {
            log.error("Error deleting risk with id: {}", id, e)
            
            // Check if it's a constraint violation (risk in use)
            if (e.message?.contains("constraint", ignoreCase = true) == true ||
                e.message?.contains("foreign key", ignoreCase = true) == true) {
                return HttpResponse.status<ErrorResponse>(HttpStatus.CONFLICT)
                    .body(ErrorResponse("CONFLICT", "Cannot delete risk - it may be in use"))
            }
            
            HttpResponse.serverError<Any>()
        }
    }
}