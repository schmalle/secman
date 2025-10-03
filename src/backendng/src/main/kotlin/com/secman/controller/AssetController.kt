package com.secman.controller

import com.secman.domain.Asset
import com.secman.domain.Vulnerability
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import com.secman.dto.PortDTO
import com.secman.dto.PortHistoryDTO
import com.secman.dto.ScanPortsDTO
import com.secman.repository.AssetRepository
import com.secman.repository.DemandRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.RiskRepository
import com.secman.repository.ScanResultRepository
import com.secman.repository.VulnerabilityRepository
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
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory

@Controller("/api/assets")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class AssetController(
    private val assetRepository: AssetRepository,
    private val demandRepository: DemandRepository,
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val riskRepository: RiskRepository,
    private val entityManager: EntityManager,
    private val scanResultRepository: ScanResultRepository,
    private val vulnerabilityRepository: VulnerabilityRepository
) {
    
    private val log = LoggerFactory.getLogger(AssetController::class.java)

    @Serdeable
    data class CreateAssetRequest(
        @NotBlank @Size(max = 255) val name: String,
        @NotBlank val type: String,
        @Nullable val ip: String? = null,
        @NotBlank @Size(max = 255) val owner: String,
        @Nullable val description: String? = null
    )

    @Serdeable
    data class UpdateAssetRequest(
        @Nullable val name: String? = null,
        @Nullable val type: String? = null,
        @Nullable val ip: String? = null,
        @Nullable val owner: String? = null,
        @Nullable val description: String? = null
    )

    @Serdeable
    data class ErrorResponse(
        val error: String
    )

    @Get
    @Transactional(readOnly = true)
    open fun list(): HttpResponse<List<Asset>> {
        return try {
            log.debug("Fetching all assets")
            
            // Get assets ordered by createdAt DESC (same as Java implementation)
            val assets = entityManager.createQuery(
                "SELECT a FROM Asset a ORDER BY a.createdAt DESC",
                Asset::class.java
            ).resultList
            
            log.debug("Found {} assets", assets.size)
            HttpResponse.ok(assets)
        } catch (e: Exception) {
            log.error("Error fetching assets", e)
            HttpResponse.serverError<List<Asset>>()
        }
    }

    @Get("/{id}")
    @Transactional(readOnly = true)
    open fun get(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching asset with id: {}", id)
            
            val asset = assetRepository.findById(id).orElse(null)
            
            if (asset != null) {
                log.debug("Found asset: {}", asset.name)
                HttpResponse.ok(asset)
            } else {
                log.debug("Asset not found with id: {}", id)
                HttpResponse.notFound(ErrorResponse("Asset not found"))
            }
        } catch (e: Exception) {
            log.error("Error fetching asset with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post
    @Transactional
    open fun create(@Valid @Body request: CreateAssetRequest): HttpResponse<*> {
        return try {
            log.debug("Creating asset with name: {}", request.name)
            
            // Validate required fields are not blank after trimming
            val trimmedName = request.name.trim()
            val trimmedType = request.type.trim()
            val trimmedOwner = request.owner.trim()
            
            if (trimmedName.isBlank()) {
                return HttpResponse.badRequest(ErrorResponse("Name cannot be empty"))
            }
            
            if (trimmedType.isBlank()) {
                return HttpResponse.badRequest(ErrorResponse("Type cannot be empty"))
            }
            
            if (trimmedOwner.isBlank()) {
                return HttpResponse.badRequest(ErrorResponse("Owner cannot be empty"))
            }
            
            // Create new asset (same structure as Java implementation)
            val asset = Asset(
                name = trimmedName,
                type = trimmedType,
                ip = request.ip?.trim()?.takeIf { it.isNotBlank() },
                owner = trimmedOwner,
                description = request.description?.trim()?.takeIf { it.isNotBlank() }
            )
            
            val savedAsset = assetRepository.save(asset)
            
            log.info("Created asset: {} with id: {}", savedAsset.name, savedAsset.id)
            HttpResponse.status<Asset>(HttpStatus.CREATED).body(savedAsset)
        } catch (e: Exception) {
            log.error("Error creating asset", e)
            HttpResponse.badRequest(ErrorResponse("Error creating asset: ${e.message}"))
        }
    }

    @Put("/{id}")
    @Transactional
    open fun update(id: Long, @Valid @Body request: UpdateAssetRequest): HttpResponse<*> {
        return try {
            log.debug("Updating asset with id: {}", id)
            
            val asset = assetRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("Asset not found"))
            
            // Update fields if provided (partial update support like Java implementation)
            request.name?.let { newName ->
                val trimmedName = newName.trim()
                if (trimmedName.isBlank()) {
                    return HttpResponse.badRequest(ErrorResponse("Name cannot be empty"))
                }
                asset.name = trimmedName
            }
            
            request.type?.let { newType ->
                val trimmedType = newType.trim()
                if (trimmedType.isBlank()) {
                    return HttpResponse.badRequest(ErrorResponse("Type cannot be empty"))
                }
                asset.type = trimmedType
            }
            
            request.ip?.let { newIp ->
                asset.ip = newIp.trim().takeIf { it.isNotBlank() }
            }
            
            request.owner?.let { newOwner ->
                val trimmedOwner = newOwner.trim()
                if (trimmedOwner.isBlank()) {
                    return HttpResponse.badRequest(ErrorResponse("Owner cannot be empty"))
                }
                asset.owner = trimmedOwner
            }
            
            request.description?.let { newDescription ->
                asset.description = newDescription.trim().takeIf { it.isNotBlank() }
            }
            
            val updatedAsset = assetRepository.update(asset)
            
            log.info("Updated asset: {} with id: {}", updatedAsset.name, updatedAsset.id)
            HttpResponse.ok(updatedAsset)
        } catch (e: Exception) {
            log.error("Error updating asset with id: {}", id, e)
            HttpResponse.badRequest(ErrorResponse("Error updating asset: ${e.message}"))
        }
    }

    @Delete("/{id}")
    @Transactional
    open fun delete(id: Long): HttpResponse<*> {
        return try {
            log.debug("Deleting asset with id: {}", id)
            
            val asset = assetRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("Asset not found"))
            
            // Check for references before deletion
            val referencingDemands = demandRepository.findByExistingAssetId(id)
            val referencingRiskAssessments = riskAssessmentRepository.findAllByInvolvedAssetId(id)
            val referencingRisks = riskRepository.findByAssetId(id)
            
            if (referencingDemands.isNotEmpty() || referencingRiskAssessments.isNotEmpty() || referencingRisks.isNotEmpty()) {
                val errorMessages = mutableListOf<String>()
                
                if (referencingDemands.isNotEmpty()) {
                    errorMessages.add("${referencingDemands.size} demand(s) reference this asset")
                }
                
                if (referencingRiskAssessments.isNotEmpty()) {
                    errorMessages.add("${referencingRiskAssessments.size} risk assessment(s) reference this asset")
                }
                
                if (referencingRisks.isNotEmpty()) {
                    errorMessages.add("${referencingRisks.size} risk(s) reference this asset")
                }
                
                val detailedMessage = "Cannot delete asset '${asset.name}' - it is referenced by: ${errorMessages.joinToString(" and ")}. Please handle these references first."
                
                log.warn("Asset deletion blocked for id: {} - {}", id, detailedMessage)
                return HttpResponse.badRequest(ErrorResponse(detailedMessage))
            }
            
            assetRepository.delete(asset)

            log.info("Deleted asset: {} with id: {}", asset.name, id)
            HttpResponse.ok(mapOf("message" to "Asset deleted successfully"))
        } catch (e: Exception) {
            log.error("Error deleting asset with id: {}", id, e)
            HttpResponse.serverError<ErrorResponse>().body(ErrorResponse("Error deleting asset: ${e.message}"))
        }
    }

    /**
     * Get port history for an asset
     *
     * GET /api/assets/{id}/ports
     * Auth: Any authenticated user
     * Response: PortHistoryDTO with scan history and port data
     *
     * Related to:
     * - Feature: 002-implement-a-parsing (Nmap Scan Import)
     * - Contract: specs/002-implement-a-parsing/contracts/asset-ports.yaml
     * - FR-011: Display port scan history
     */
    @Get("/{id}/ports")
    @Transactional(readOnly = true)
    open fun getPortHistory(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching port history for asset id: {}", id)

            val asset = assetRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("Asset not found"))

            // Get scan results ordered by discoveredAt DESC (newest first)
            val scanResults = scanResultRepository.findByAssetIdOrderByDiscoveredAtDesc(id)

            // Convert to DTOs
            val scans = scanResults.map { result ->
                val ports = result.ports.map { port ->
                    PortDTO(
                        portNumber = port.portNumber,
                        protocol = port.protocol,
                        state = port.state,
                        service = port.service,
                        version = port.version
                    )
                }

                ScanPortsDTO(
                    scanId = result.scan.id!!,
                    scanDate = result.discoveredAt,
                    scanType = result.scan.scanType,
                    ports = ports
                )
            }

            val response = PortHistoryDTO(
                assetId = asset.id!!,
                assetName = asset.name,
                scans = scans
            )

            log.debug("Found {} scans for asset {}", scans.size, asset.name)
            HttpResponse.ok(response)

        } catch (e: Exception) {
            log.error("Error fetching port history for asset id: {}", id, e)
            HttpResponse.serverError<ErrorResponse>().body(ErrorResponse("Error fetching port history: ${e.message}"))
        }
    }

    /**
     * Get vulnerabilities for an asset
     *
     * GET /api/assets/{assetId}/vulnerabilities
     * Auth: Any authenticated user
     * Response: Page<Vulnerability> with pagination support
     *
     * Related to:
     * - Feature: 003-i-want-to (Vulnerability Management System)
     * - Contract: specs/003-i-want-to/contracts/get-asset-vulnerabilities.yaml
     * - FR-002: Display vulnerabilities in asset inventory
     */
    @Get("/{assetId}/vulnerabilities")
    @Transactional(readOnly = true)
    open fun getVulnerabilities(
        assetId: Long,
        @Nullable pageable: Pageable?
    ): HttpResponse<*> {
        return try {
            log.debug("Fetching vulnerabilities for asset id: {}", assetId)

            // Check if asset exists
            val asset = assetRepository.findById(assetId).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("Asset not found"))

            // Use default pagination if not provided (page 0, size 20)
            val effectivePageable = pageable ?: Pageable.from(0, 20)

            // Fetch vulnerabilities with pagination
            val vulnerabilities = vulnerabilityRepository.findByAssetId(assetId, effectivePageable)

            log.debug("Found {} vulnerabilities for asset {}", vulnerabilities.totalSize, asset.name)
            HttpResponse.ok(vulnerabilities)

        } catch (e: Exception) {
            log.error("Error fetching vulnerabilities for asset id: {}", assetId, e)
            HttpResponse.serverError<ErrorResponse>().body(ErrorResponse("Error fetching vulnerabilities: ${e.message}"))
        }
    }
}