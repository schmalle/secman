package com.secman.controller

import com.secman.domain.Asset
import com.secman.domain.Criticality
import com.secman.domain.NetworkZone
import com.secman.domain.Vulnerability
import com.secman.dto.*
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import com.secman.repository.AssetRepository
import com.secman.repository.DemandRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.RiskRepository
import com.secman.repository.ScanResultRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.service.AssetFilterService
import com.secman.service.AssetCascadeDeleteService
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.annotation.Transactional
import jakarta.persistence.EntityManager
import jakarta.persistence.PessimisticLockException
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
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val assetFilterService: AssetFilterService,
    private val userRepository: UserRepository,
    private val workgroupRepository: com.secman.repository.WorkgroupRepository,
    private val assetBulkDeleteService: com.secman.service.AssetBulkDeleteService,
    private val assetExportService: com.secman.service.AssetExportService,
    private val assetCascadeDeleteService: com.secman.service.AssetCascadeDeleteService,
    private val assetMergeService: com.secman.service.AssetMergeService
) {
    
    private val log = LoggerFactory.getLogger(AssetController::class.java)

    @Serdeable
    data class CreateAssetRequest(
        @NotBlank @Size(max = 255) val name: String,
        @NotBlank val type: String,
        @Nullable val ip: String? = null,
        @NotBlank @Size(max = 255) val owner: String,
        @Nullable val description: String? = null,
        @Nullable val criticality: Criticality? = null,
        @Nullable val adDomain: String? = null,
        @Nullable val networkZone: NetworkZone? = null
    )

    @Serdeable
    data class UpdateAssetRequest(
        @Nullable val name: String? = null,
        @Nullable val type: String? = null,
        @Nullable val ip: String? = null,
        @Nullable val owner: String? = null,
        @Nullable val description: String? = null,
        @Nullable val workgroupIds: List<Long>? = null,
        @Nullable val criticality: Criticality? = null,
        @Nullable val adDomain: String? = null,
        @Nullable val networkZone: NetworkZone? = null
    )

    @Serdeable
    data class ImportAssetRequest(
        @NotBlank @Size(max = 255) val name: String,
        @NotBlank val type: String,
        @NotBlank @Size(max = 255) val owner: String,
        @Nullable val ip: String? = null,
        @Nullable val description: String? = null,
        @Nullable val networkZone: NetworkZone? = null,
        @Nullable val tags: Map<String, String>? = null
    )

    @Serdeable
    data class ImportAssetResponse(
        val asset: AssetResponse,
        val created: Boolean
    )

    @Serdeable
    data class ErrorResponse(
        val error: String
    )

    @Serdeable
    data class OwnerCandidate(
        val value: String,
        val label: String
    )

    /**
     * Response DTO for Asset entity to prevent exposing internal JPA fields.
     * Security finding HI-8: Excludes ipNumeric, vulnerabilities, scanResults,
     * workgroup details, manualCreator details, scanUploader details, cloudInstanceId, osVersion.
     */
    @Serdeable
    data class AssetResponse(
        val id: Long,
        val name: String,
        val type: String,
        val ip: String?,
        val owner: String,
        val description: String?,
        val lastSeen: String?,
        val groups: String?,
        val criticality: Criticality?,
        val cloudAccountId: String?,
        val cloudInstanceId: String?,
        val adDomain: String?,
        val createdAt: String?,
        val workgroups: List<WorkgroupSummary>? = null,
        val networkZone: NetworkZone? = null,
        val openPortCount: Int? = null,
        val lastScanType: String? = null,
        val lastScanDate: String? = null,
        val tags: List<TagSummary>? = null
    ) {
        @Serdeable
        data class WorkgroupSummary(
            val id: Long,
            val name: String
        )

        @Serdeable
        data class TagSummary(
            val key: String,
            val value: String
        )

        companion object {
            fun from(asset: Asset): AssetResponse {
                // Access workgroups safely - may be lazy-loaded
                val workgroupSummaries = try {
                    asset.workgroups.map { WorkgroupSummary(it.id!!, it.name) }
                } catch (e: Exception) {
                    null
                }

                val tagSummaries = try {
                    asset.tags.map { TagSummary(it.key, it.value) }
                } catch (e: Exception) {
                    null
                }

                return AssetResponse(
                    id = asset.id!!,
                    name = asset.name,
                    type = asset.type,
                    ip = asset.ip,
                    owner = asset.owner,
                    description = asset.description,
                    lastSeen = asset.lastSeen?.toString(),
                    groups = asset.groups,
                    criticality = asset.criticality,
                    cloudAccountId = asset.cloudAccountId,
                    cloudInstanceId = asset.cloudInstanceId,
                    adDomain = asset.adDomain,
                    createdAt = asset.createdAt?.toString(),
                    workgroups = workgroupSummaries,
                    networkZone = asset.networkZone,
                    openPortCount = asset.openPortCount,
                    lastScanType = asset.lastScanType,
                    lastScanDate = asset.lastScanDate?.toString(),
                    tags = tagSummaries
                )
            }
        }
    }

    /**
     * Get list of valid owner candidates for the asset owner select box.
     * Returns "CrowdStrike Import" plus all system users sorted by username.
     * Available to ADMIN and SECCHAMPION roles.
     */
    @Get("/owner-candidates")
    @Secured("ADMIN", "SECCHAMPION")
    fun getOwnerCandidates(): HttpResponse<List<OwnerCandidate>> {
        val candidates = mutableListOf<OwnerCandidate>()
        candidates.add(OwnerCandidate("CrowdStrike Import", "CrowdStrike Import"))
        val users = userRepository.findAll().sortedBy { it.username.lowercase() }
        for (user in users) {
            candidates.add(OwnerCandidate(user.username, "${user.username} (${user.email})"))
        }
        return HttpResponse.ok(candidates)
    }

    /**
     * List assets accessible to the authenticated user
     * Feature: 008-create-an-additional (Workgroup-Based Access Control)
     *
     * FR-013, FR-016, FR-017: Filter by workgroup membership + ownership
     * ADMIN sees all, regular users and VULN see their workgroup assets + owned assets
     */
    @Get
    @Transactional(readOnly = true)
    open fun list(authentication: Authentication): HttpResponse<List<AssetResponse>> {
        return try {
            log.debug("Fetching accessible assets for user: {}", authentication.name)

            // Use AssetFilterService for workgroup-based filtering
            val assets = assetFilterService.getAccessibleAssets(authentication)
                .sortedByDescending { it.createdAt }
                .map { AssetResponse.from(it) }

            log.debug("Found {} accessible assets for user {}", assets.size, authentication.name)
            HttpResponse.ok(assets)
        } catch (e: Exception) {
            log.error("Error fetching assets for user: {}", authentication.name, e)
            HttpResponse.serverError<List<AssetResponse>>()
        }
    }

    /**
     * Get internet-facing assets (EXTERNAL or DMZ network zone) that have an IP address.
     * Used by the CLI port-scan command to determine scan targets.
     *
     * GET /api/assets/internet-facing
     * Auth: ADMIN role required (port scanning is an admin operation)
     */
    @Get("/internet-facing")
    @Secured("ADMIN")
    @Transactional(readOnly = true)
    open fun getInternetFacingAssets(): HttpResponse<List<AssetResponse>> {
        return try {
            log.debug("Fetching internet-facing assets")
            val assets = assetRepository.findInternetFacingWithIp()
                .map { AssetResponse.from(it) }
            log.debug("Found {} internet-facing assets", assets.size)
            HttpResponse.ok(assets)
        } catch (e: Exception) {
            log.error("Error fetching internet-facing assets", e)
            HttpResponse.serverError<List<AssetResponse>>()
        }
    }

    /**
     * Get asset by ID with access control
     * Feature: 008-create-an-additional (Workgroup-Based Access Control)
     *
     * FR-020: Verify asset access before detail view
     */
    @Get("/{id}")
    @Transactional(readOnly = true)
    open fun get(id: Long, authentication: Authentication): HttpResponse<*> {
        return try {
            log.debug("Fetching asset with id: {} for user: {}", id, authentication.name)

            val asset = assetRepository.findById(id).orElse(null)

            if (asset == null) {
                log.debug("Asset not found with id: {}", id)
                return HttpResponse.notFound(ErrorResponse("Asset not found"))
            }

            // Check if user can access this asset (workgroup-based access control)
            if (!assetFilterService.canAccessAsset(id, authentication)) {
                log.warn("User {} denied access to asset {}", authentication.name, id)
                return HttpResponse.notFound(ErrorResponse("Asset not found"))
            }

            log.debug("Found asset: {}", asset.name)
            HttpResponse.ok(AssetResponse.from(asset))
        } catch (e: Exception) {
            log.error("Error fetching asset with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    /**
     * Get asset by name with access control
     * Used by CrowdStrike Vulnerability Lookup to load asset details for editing.
     */
    @Get("/by-name/{name}")
    @Transactional(readOnly = true)
    open fun getByName(name: String, authentication: Authentication): HttpResponse<*> {
        return try {
            log.debug("Fetching asset by name: {} for user: {}", name, authentication.name)

            val asset = assetRepository.findByNameIgnoreCase(name)

            if (asset == null) {
                return HttpResponse.notFound(ErrorResponse("Asset not found"))
            }

            if (!assetFilterService.canAccessAsset(asset.id!!, authentication)) {
                log.warn("User {} denied access to asset {}", authentication.name, name)
                return HttpResponse.notFound(ErrorResponse("Asset not found"))
            }

            HttpResponse.ok(AssetResponse.from(asset))
        } catch (e: Exception) {
            log.error("Error fetching asset by name: {}", name, e)
            HttpResponse.serverError<Any>()
        }
    }

    /**
     * Idempotent asset import endpoint for external tools (recon-agent, scanners).
     *
     * Looks up by name (case-insensitive). If found, merges new data into existing
     * asset while preserving operator-set fields. If not found, creates new.
     * Tags are merged additively (update existing keys, add new, never delete).
     *
     * PUT /api/assets/import
     * Auth: ADMIN role required
     */
    @Put("/import")
    @Secured("ADMIN")
    @Transactional
    open fun importAsset(@Valid @Body request: ImportAssetRequest, authentication: Authentication): HttpResponse<*> {
        return try {
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

            val (asset, created) = assetMergeService.importAsset(
                name = trimmedName,
                type = trimmedType,
                owner = trimmedOwner,
                ip = request.ip?.trim()?.takeIf { it.isNotBlank() },
                description = request.description?.trim()?.takeIf { it.isNotBlank() },
                networkZone = request.networkZone,
                tags = request.tags
            )

            log.info("Asset import: {} {} (id={}) by user: {}",
                if (created) "created" else "updated", asset.name, asset.id, authentication.name)

            val response = ImportAssetResponse(
                asset = AssetResponse.from(asset),
                created = created
            )
            HttpResponse.ok(response)
        } catch (e: Exception) {
            log.error("Error importing asset: {}", request.name, e)
            HttpResponse.badRequest(ErrorResponse("An internal error occurred"))
        }
    }

    /**
     * Create asset with manual creator tracking
     * Feature: 008-create-an-additional (Workgroup-Based Access Control)
     *
     * FR-023: Track manual creator for ownership-based access
     */
    @Post
    @Transactional
    open fun create(@Valid @Body request: CreateAssetRequest, authentication: Authentication): HttpResponse<*> {
        return try {
            log.debug("Creating asset with name: {} for user: {}", request.name, authentication.name)

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

            // Get user ID from authentication for manual creator tracking
            val userId = authentication.attributes["userId"]?.toString()?.toLongOrNull()
            val manualCreator = if (userId != null) {
                userRepository.findById(userId).orElse(null)
            } else {
                null
            }

            // Create new asset with manual creator tracking
            val asset = Asset(
                name = trimmedName,
                type = trimmedType,
                ip = request.ip?.trim()?.takeIf { it.isNotBlank() },
                owner = trimmedOwner,
                description = request.description?.trim()?.takeIf { it.isNotBlank() },
                criticality = request.criticality,
                manualCreator = manualCreator,
                networkZone = request.networkZone
            )
            asset.adDomain = request.adDomain?.trim()?.takeIf { it.isNotBlank() }

            val savedAsset = assetRepository.save(asset)

            log.info("Created asset: {} with id: {} by user: {}", savedAsset.name, savedAsset.id, authentication.name)
            HttpResponse.status<AssetResponse>(HttpStatus.CREATED).body(AssetResponse.from(savedAsset))
        } catch (e: Exception) {
            log.error("Error creating asset", e)
            HttpResponse.badRequest(ErrorResponse("An internal error occurred"))
        }
    }

    @Put("/{id}")
    @Transactional
    open fun update(id: Long, @Valid @Body request: UpdateAssetRequest, authentication: Authentication): HttpResponse<*> {
        return try {
            log.debug("Updating asset with id: {} for user: {}", id, authentication.name)

            // Access control: verify user can access this asset
            if (!assetFilterService.canAccessAsset(id, authentication)) {
                log.warn("User {} denied update access to asset {}", authentication.name, id)
                return HttpResponse.notFound(ErrorResponse("Asset not found"))
            }

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

            // Feature 039: Handle criticality update (null explicitly allowed to revert to inheritance)
            if (request.criticality !== null) {
                asset.criticality = request.criticality
            }

            // Feature 053: Handle adDomain update
            request.adDomain?.let { newAdDomain ->
                asset.adDomain = newAdDomain.trim().takeIf { it.isNotBlank() }
            }

            // Handle networkZone update
            request.networkZone?.let { newNetworkZone ->
                asset.networkZone = newNetworkZone
            }

            request.workgroupIds?.let { workgroupIds ->
                // Workgroup reassignment requires ADMIN role
                val roles = authentication.roles
                if (!roles.contains("ADMIN")) {
                    return HttpResponse.status<ErrorResponse>(HttpStatus.FORBIDDEN)
                        .body(ErrorResponse("Workgroup reassignment requires ADMIN role"))
                }

                val workgroups = workgroupIds.mapNotNull { id ->
                    workgroupRepository.findById(id).orElse(null)
                }

                if (workgroups.size != workgroupIds.size) {
                    return HttpResponse.badRequest(ErrorResponse("One or more workgroup IDs not found"))
                }

                // Update workgroup assignments
                asset.workgroups.clear()
                asset.workgroups.addAll(workgroups)
            }

            val updatedAsset = assetRepository.update(asset)

            log.info("Updated asset: {} with id: {}", updatedAsset.name, updatedAsset.id)
            HttpResponse.ok(AssetResponse.from(updatedAsset))
        } catch (e: Exception) {
            log.error("Error updating asset with id: {}", id, e)
            HttpResponse.badRequest(ErrorResponse("An internal error occurred"))
        }
    }

    /**
     * Get cascade deletion summary for pre-flight validation
     * Feature: 033-cascade-asset-deletion (User Story 4 - UI Warning Before Cascade Deletion)
     *
     * GET /api/assets/{id}/cascade-summary
     * Auth: Any authenticated user (ADMIN recommended)
     * Response: CascadeDeleteSummaryDto
     *
     * Related Requirements:
     * - FR-012: System MUST perform a pre-flight count of related records
     * - Contract: contracts/cascade-delete-api.yaml
     *
     * Error Responses:
     * - 404: Asset not found
     * - 500: Internal server error
     */
    @Get("/{id}/cascade-summary")
    @Secured("ADMIN")
    @Transactional(readOnly = true)
    open fun getCascadeSummary(id: Long): HttpResponse<*> {
        return try {
            log.debug("Getting cascade summary for asset id: {}", id)

            val summary = assetCascadeDeleteService.getCascadeSummary(id)

            log.info("Cascade summary for asset {}: {} vulns, {} exceptions, {} requests, estimated {}s",
                id, summary.vulnerabilitiesCount, summary.assetExceptionsCount,
                summary.exceptionRequestsCount, summary.estimatedDurationSeconds)

            HttpResponse.ok(summary)

        } catch (e: AssetCascadeDeleteService.AssetNotFoundException) {
            log.warn("Asset not found for cascade summary: {}", id)
            HttpResponse.notFound(ErrorResponse(e.message ?: "Asset not found"))

        } catch (e: Exception) {
            log.error("Error getting cascade summary for asset: {}", id, e)
            HttpResponse.serverError<ErrorResponse>()
                .body(ErrorResponse("An internal error occurred"))
        }
    }

    /**
     * Delete asset with cascade deletion of all related data
     * Feature: 033-cascade-asset-deletion (User Story 1 - Delete Asset with All Related Data)
     *
     * DELETE /api/assets/{id}
     * Auth: ADMIN role required
     * Response: CascadeDeletionResultDto
     *
     * Related Requirements:
     * - FR-001: System MUST cascade delete vulnerabilities when asset is deleted
     * - FR-002: System MUST cascade delete ASSET-type exceptions
     * - FR-003: System MUST cascade delete vulnerability exception requests
     * - FR-011: Use pessimistic row-level locking to prevent concurrent deletion
     * - FR-013: Provide detailed structured error messages
     * - Contract: contracts/cascade-delete-api.yaml
     *
     * Error Responses:
     * - 403: User does not have ADMIN role
     * - 404: Asset not found
     * - 409: Asset is locked by another transaction (concurrent deletion)
     * - 422: Deletion would exceed timeout (forceTimeout parameter can override)
     * - 500: Internal server error or transaction timeout
     */
    @Delete("/{id}")
    @Secured("ADMIN")
    open fun delete(
        id: Long,
        @QueryValue(defaultValue = "false") forceTimeout: Boolean,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            log.info("Cascade delete request for asset {} by user {} (forceTimeout={})",
                id, authentication.name, forceTimeout)

            // Check for non-cascade references before deletion (demands, risk assessments, risks)
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

                // Get asset name for error message
                val asset = assetRepository.findById(id).orElse(null)
                val assetName = asset?.name ?: "Asset #$id"

                val detailedMessage = "Cannot delete asset '$assetName' - it is referenced by: ${errorMessages.joinToString(" and ")}. Please handle these references first."

                log.warn("Asset deletion blocked for id: {} - {}", id, detailedMessage)
                return HttpResponse.badRequest(ErrorResponse(detailedMessage))
            }

            // Perform cascade deletion
            val result = assetCascadeDeleteService.deleteAsset(
                assetId = id,
                username = authentication.name,
                forceTimeout = forceTimeout
            )

            log.info("Cascade delete successful for asset {}: {} vulns, {} exceptions, {} requests deleted",
                id, result.deletedVulnerabilities, result.deletedExceptions, result.deletedRequests)

            HttpResponse.ok(result)

        } catch (e: AssetCascadeDeleteService.AssetNotFoundException) {
            log.warn("Asset not found for deletion: {}", id)
            HttpResponse.notFound(ErrorResponse(e.message ?: "Asset not found"))

        } catch (e: PessimisticLockException) {
            log.warn("Asset {} is locked by another transaction", id)
            val asset = assetRepository.findById(id).orElse(null)
            val errorDto = assetCascadeDeleteService.buildLockedErrorDto(
                assetId = id,
                assetName = asset?.name ?: "Asset #$id",
                cause = "Asset is currently being deleted by another user"
            )
            HttpResponse.status<DeletionErrorDto>(HttpStatus.CONFLICT).body(errorDto)

        } catch (e: AssetCascadeDeleteService.TimeoutWarningException) {
            log.warn("Asset {} deletion would exceed timeout: {}s", id, e.estimatedSeconds)
            val errorDto = assetCascadeDeleteService.buildTimeoutWarningDto(
                assetId = e.assetId,
                assetName = e.assetName,
                estimatedSeconds = e.estimatedSeconds
            )
            HttpResponse.status<DeletionErrorDto>(HttpStatus.UNPROCESSABLE_ENTITY).body(errorDto)

        } catch (e: Exception) {
            log.error("Failed to delete asset {}", id, e)
            val asset = assetRepository.findById(id).orElse(null)
            val errorDto = assetCascadeDeleteService.buildInternalErrorDto(
                assetId = id,
                assetName = asset?.name ?: "Asset #$id",
                cause = "Deletion failed due to internal error",
                technicalDetails = "An internal error occurred"
            )
            HttpResponse.serverError<DeletionErrorDto>().body(errorDto)
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
    open fun getPortHistory(id: Long, authentication: Authentication): HttpResponse<*> {
        return try {
            log.debug("Fetching port history for asset id: {} for user: {}", id, authentication.name)

            // Access control: verify user can access this asset
            if (!assetFilterService.canAccessAsset(id, authentication)) {
                log.warn("User {} denied access to port history for asset {}", authentication.name, id)
                return HttpResponse.notFound(ErrorResponse("Asset not found"))
            }

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
            HttpResponse.serverError<ErrorResponse>().body(ErrorResponse("An internal error occurred"))
        }
    }

    /**
     * Get vulnerabilities for an asset with access control
     * Feature: 008-create-an-additional (Workgroup-Based Access Control)
     *
     * GET /api/assets/{assetId}/vulnerabilities
     * Auth: Any authenticated user
     * Response: List<Vulnerability>
     *
     * Related to:
     * - Feature: 003-i-want-to (Vulnerability Management System)
     * - Feature: 008-create-an-additional (Workgroup-Based Access Control)
     * - FR-021: Filter asset vulnerabilities by accessibility
     */
    @Get("/{assetId}/vulnerabilities")
    @Transactional(readOnly = true)
    open fun getVulnerabilities(
        assetId: Long,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            log.debug("Fetching vulnerabilities for asset id: {} for user: {}", assetId, authentication.name)

            // Use AssetFilterService for access-controlled vulnerability retrieval
            val vulnerabilities = assetFilterService.getAssetVulnerabilities(assetId, authentication)

            if (vulnerabilities.isEmpty()) {
                // Could mean asset not found OR user doesn't have access
                val asset = assetRepository.findById(assetId).orElse(null)
                if (asset == null) {
                    return HttpResponse.notFound(ErrorResponse("Asset not found"))
                }
                // Asset exists but user can't access it - return empty list
                log.debug("User {} has no access to asset {}", authentication.name, assetId)
            }

            log.debug("Found {} vulnerabilities for asset {}", vulnerabilities.size, assetId)
            HttpResponse.ok(vulnerabilities)

        } catch (e: Exception) {
            log.error("Error fetching vulnerabilities for asset id: {}", assetId, e)
            HttpResponse.serverError<ErrorResponse>().body(ErrorResponse("An internal error occurred"))
        }
    }

    /**
     * Export assets to Excel file
     * Feature: 029-asset-bulk-operations (User Story 2 - Export Assets to File)
     *
     * GET /api/assets/export
     * Auth: Any authenticated user
     * Response: Binary Excel file (.xlsx)
     *
     * Related Requirements:
     * - FR-010: Export assets to Excel with all fields
     * - FR-011: Apply workgroup-based access control (ADMIN sees all, non-ADMIN sees workgroup+owned)
     * - FR-012: Format export file with clear column headers
     * - FR-014: Provide user feedback during export (loading indicators handled by frontend)
     * - FR-015: Display error when no assets available
     *
     * Error Responses:
     * - 400: No assets available to export
     * - 401: User not authenticated
     * - 500: Export failed
     */
    @Get("/export")
    open fun exportAssets(authentication: Authentication): HttpResponse<*> {
        return try {
            log.info("Asset export request from user: {}", authentication.name)

            // Get assets with workgroup filtering
            val dtos = assetExportService.exportAssets(authentication)

            // Check if any assets are available
            if (dtos.isEmpty()) {
                log.warn("No assets available to export for user: {}", authentication.name)
                return HttpResponse.badRequest(ErrorResponse("No assets available to export"))
            }

            // Write to Excel
            val outputStream = assetExportService.writeToExcel(dtos)

            // Generate filename with current date
            val dateStr = java.time.LocalDate.now().toString()
            val filename = "assets_export_$dateStr.xlsx"

            log.info("Asset export successful: {} assets exported for user {}", dtos.size, authentication.name)

            // Return binary Excel file with proper headers
            HttpResponse.ok(outputStream.toByteArray())
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition", "attachment; filename=\"$filename\"")

        } catch (e: Exception) {
            log.error("Asset export failed for user: {}", authentication.name, e)
            HttpResponse.serverError<ErrorResponse>()
                .body(ErrorResponse("An internal error occurred"))
        }
    }

    /**
     * Bulk delete all assets (ADMIN only)
     * Feature: 029-asset-bulk-operations (User Story 1 - Bulk Delete Assets)
     *
     * DELETE /api/assets/bulk
     * Auth: ADMIN role required
     * Response: BulkDeleteResult
     *
     * Related Requirements:
     * - FR-001: Delete All Assets button visible only to ADMIN users
     * - FR-003: Delete all assets when ADMIN confirms
     * - FR-006: Prevent non-ADMIN users from accessing bulk delete
     * - FR-007: Handle cascade deletion (vulnerabilities, scan results)
     * - FR-008: Execute within transaction with rollback on failure
     *
     * Error Responses:
     * - 403: User does not have ADMIN role
     * - 409: Another bulk delete operation is in progress
     * - 500: Transaction failed and was rolled back
     */
    @Delete("/bulk")
    @Secured("ADMIN")
    open fun bulkDeleteAssets(authentication: Authentication): HttpResponse<*> {
        return try {
            log.info("Bulk delete request received from user: {}", authentication.name)

            val result = assetBulkDeleteService.deleteAllAssets()

            log.info("Bulk delete successful: {}", result.message)
            HttpResponse.ok(result)

        } catch (e: com.secman.service.AssetBulkDeleteService.ConcurrentOperationException) {
            log.warn("Concurrent bulk delete attempt rejected: {}", e.message)
            HttpResponse.status<ErrorResponse>(HttpStatus.CONFLICT)
                .body(ErrorResponse(e.message ?: "Bulk asset deletion already in progress"))

        } catch (e: Exception) {
            log.error("Bulk delete failed", e)
            HttpResponse.serverError<ErrorResponse>()
                .body(ErrorResponse("Bulk delete failed. No assets were deleted."))
        }
    }
}