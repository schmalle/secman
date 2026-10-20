package com.secman.controller

import com.secman.dto.CrowdStrikeImportStatusDto
import com.secman.dto.CrowdStrikeQueryResponse
import com.secman.dto.CrowdStrikeSaveRequest
import com.secman.dto.CrowdStrikeSaveResponse
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.dto.ImportStatisticsDto
import com.secman.dto.ReconcileJobStartedResponse
import com.secman.dto.ReconcileStaleVulnerabilitiesRequest
import com.secman.service.CrowdStrikeError
import com.secman.service.CrowdStrikeQueryService
import com.secman.service.CrowdStrikeReconcileJobService
import com.secman.service.CrowdStrikeVulnerabilityImportService
import com.secman.service.CrowdStrikeVulnerabilityService
import com.secman.service.ReconcileJobConflictException
import com.secman.util.InputDetectionUtils
import com.secman.util.ValidationUtils
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory

/**
 * REST controller for CrowdStrike Falcon API integration
 *
 * Endpoints:
 * - GET /api/vulnerabilities - Query vulnerabilities by hostname (with filtering & pagination)
 * - GET /api/crowdstrike/vulnerabilities - Query vulnerabilities by hostname (legacy)
 * - POST /api/crowdstrike/vulnerabilities/save - Save vulnerabilities to database (single or batch)
 *
 * - Feature 023-create-in-the (Phase 5: Backend API Integration)
 * - Feature 032-servers-query-import (Batch Import)
 */
@Controller("/api")
@Secured("ADMIN", "VULN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class CrowdStrikeController(
    private val crowdStrikeService: CrowdStrikeVulnerabilityService,
    private val queryService: CrowdStrikeQueryService,
    private val importService: CrowdStrikeVulnerabilityImportService,
    private val reconcileJobService: CrowdStrikeReconcileJobService,
    private val syncService: com.secman.service.CrowdStrikeSyncService,
    private val assetRepository: com.secman.repository.AssetRepository,
    private val assetFilterService: com.secman.service.AssetFilterService,
    private val identityRepository: com.secman.repository.CrowdStrikeAssetIdentityRepository
) {
    private val log = LoggerFactory.getLogger(CrowdStrikeController::class.java)

    @jakarta.inject.Inject
    internal lateinit var importRecoveryService: com.secman.service.CrowdStrikeImportRecoveryService

    /**
     * Query CrowdStrike for vulnerabilities with filtering and pagination
     *
     * Supports both hostname and AWS EC2 Instance ID queries (Feature 041)
     *
     * @param hostname System hostname or AWS instance ID to query
     * @param severity Optional severity filter (critical, high, medium, low)
     * @param product Optional product filter (substring match)
     * @param limit Result limit (default: 100, max: 1000)
     * @param force If true ("Refresh" button), queries CrowdStrike Falcon live, bypassing both the
     *              persisted vulnerability rows and the vulnerability_queries cache. If Falcon has
     *              no device for the host, the persisted rows are returned with a `notice` rather
     *              than a 404 (Feature 041, Task T035)
     * @return CrowdStrikeQueryResponse with vulnerabilities
     *
     * Error responses:
     * - 400: Invalid parameters (including invalid instance ID format)
     * - 401: Unauthorized
     * - 403: Forbidden
     * - 404: Hostname/Instance ID not found
     * - 429: Rate limit exceeded
     * - 500: Server error
     */
    @Get("/vulnerabilities")
    open fun queryAuthorizedVulnerabilities(
        @QueryValue hostname: String?,
        @QueryValue severity: String? = null,
        @QueryValue product: String? = null,
        @QueryValue limit: Int? = null,
        @QueryValue page: Int? = null,
        @QueryValue(defaultValue = "false") force: Boolean = false,
        @QueryValue assetId: Long? = null,
        authentication: Authentication
    ): HttpResponse<*> {
        var selected = assetId?.let { id ->
            if (!assetFilterService.canAccessAsset(id, authentication)) return HttpResponse.notFound<Any>()
            assetRepository.findById(id).orElse(null) ?: return HttpResponse.notFound<Any>()
        }
        var identifier = selected?.cloudInstanceId ?: selected?.crowdStrikeHostname ?: selected?.name ?: hostname
        if (!authentication.roles.contains("ADMIN") && selected == null) {
            if (identifier.isNullOrBlank()) return HttpResponse.badRequest(mapOf("error" to "System is required"))
            val asset = if (InputDetectionUtils.isAwsInstanceId(identifier)) assetRepository.findByCloudInstanceIdIgnoreCase(identifier)
                else {
                    val candidates = assetRepository.findCrowdStrikeReplacementCandidates(identifier,
                        io.micronaut.data.model.Pageable.from(0, 102))
                    if (candidates.totalSize > 101) return HttpResponse.badRequest(mapOf("error" to "Use an asset ID"))
                    candidates.content.filter { it.id?.let { id -> assetFilterService.canAccessAsset(id, authentication) } == true }
                        .maxByOrNull { it.crowdStrikeAgentSeenAt ?: java.time.LocalDateTime.MIN }
                }
            if (asset?.id == null || !assetFilterService.canAccessAsset(requireNotNull(asset.id), authentication)) {
                return HttpResponse.notFound<Any>()
            }
            selected = asset
            identifier = asset.cloudInstanceId ?: asset.crowdStrikeHostname ?: asset.name
        }
        val grantedAids = if (selected != null) {
            identityRepository.findByAssetIdIn(listOf(requireNotNull(selected.id))).map { it.crowdStrikeAid }.toSet()
        } else emptySet()
        if (selected != null) {
            if ((limit != null && limit !in 1..20000) || (page != null && page < 0))
                return HttpResponse.badRequest(mapOf("error" to "Invalid pagination"))
            return try {
                val exact = queryService.queryAsset(selected, grantedAids.singleOrNull(), force, severity, product,
                    limit ?: 20000, page ?: 0) ?: return HttpResponse.notFound<Any>()
                if (!com.secman.service.AssetCurrentnessPolicy.includes(exact.observedAt?.let {
                        java.time.LocalDateTime.ofInstant(it, java.time.ZoneOffset.UTC)
                    })) return HttpResponse.notFound<Any>()
                if (exact.assetId != selected.id) return HttpResponse.notFound<Any>()
                if (exact.dataSource == "LIVE_API" && (exact.selectedAid !in grantedAids ||
                    (!selected.cloudAccountId.isNullOrBlank() && !selected.cloudAccountId.equals(exact.cloudAccountId, true))))
                    return HttpResponse.notFound<Any>()
                HttpResponse.ok(exact.copy(assetId = selected.id, matchingDevices = emptySet()))
            } catch (e: Exception) {
                log.warn("Exact Falcon lookup actor={} assetId={} outcome=failed", authentication.name.replace('\r', '_').replace('\n', '_'), selected.id)
                HttpResponse.notFound<Any>()
            }
        }
        val result = queryVulnerabilities(identifier, severity, product, limit, page, force)
        val body = result.body() as? CrowdStrikeQueryResponse ?: return result
        if (!com.secman.service.AssetCurrentnessPolicy.includes(body.observedAt?.let {
                java.time.LocalDateTime.ofInstant(it, java.time.ZoneOffset.UTC)
            })) return HttpResponse.notFound<Any>()
        return HttpResponse.ok(body)
    }

    /** Provider-wide discovery is reserved for admins; asset lookups use the scoped endpoint. */
    @Get("/crowdstrike/devices")
    @Secured("ADMIN")
    open fun queryDevices(@QueryValue hostname: String?, @QueryValue aid: String? = null,
        @QueryValue(defaultValue = "false") allMatches: Boolean = false): HttpResponse<*> = try {
        require(aid == null || aid.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid AID" }
        require(!(aid != null && allMatches)) { "Choose an AID or all matches" }
        val name = hostname?.let(::sanitizeHostname)
        require(name != null || aid != null) { "Hostname or AID is required" }
        HttpResponse.ok(queryService.queryDevicesLive(name, aid, allMatches))
    } catch (e: IllegalArgumentException) {
        HttpResponse.badRequest(mapOf("error" to "Invalid device selection"))
    } catch (e: Exception) {
        log.warn("Falcon explicit device query outcome=failed", e)
        HttpResponse.status<Any>(HttpStatus.BAD_GATEWAY)
    }

    /** Read-only preview: never moves bindings or deletes legacy records. */
    @Get("/crowdstrike/identity-preview")
    @Secured("ADMIN")
    open fun previewIdentities(@QueryValue hostname: String): HttpResponse<*> {
        val name = try { sanitizeHostname(hostname) } catch (e: IllegalArgumentException) {
            return HttpResponse.badRequest(mapOf("error" to "Invalid hostname"))
        }
        val candidates = assetRepository.findCrowdStrikeReplacementCandidates(name, io.micronaut.data.model.Pageable.from(0, 102))
        if (candidates.totalSize > 101) return HttpResponse.badRequest(mapOf("error" to "Too many matches; use asset IDs"))
        val bindings = if (candidates.content.isEmpty()) emptyList() else
            identityRepository.findByAssetIdIn(candidates.content.map { requireNotNull(it.id) })
        return HttpResponse.ok(candidates.content.map { asset ->
            val owned = bindings.filter { it.asset.id == asset.id }
            mapOf("assetId" to asset.id, "hostname" to (asset.crowdStrikeHostname ?: asset.name),
                "instanceId" to asset.cloudInstanceId, "accountId" to asset.cloudAccountId,
                "aids" to owned.map { it.crowdStrikeAid }, "lastSeen" to asset.crowdStrikeAgentSeenAt,
                "classification" to when {
                    owned.isEmpty() -> "LEGACY_UNBOUND_REVIEW"
                    owned.size > 1 -> "MULTIPLE_BINDINGS_REVIEW"
                    owned.single().falconFirstSeenAt == null -> "UNVERIFIED_BINDING_REVIEW"
                    else -> "BOUND_DEVICE_PRESERVE"
                })
        })
    }

    open fun queryVulnerabilities(
        @QueryValue hostname: String?,
        @QueryValue severity: String? = null,
        @QueryValue product: String? = null,
        @QueryValue limit: Int? = null,
        @QueryValue page: Int? = null,
        @QueryValue(defaultValue = "false") force: Boolean = false
    ): HttpResponse<*> {
        log.info(
            "Received vulnerability query: input={}, severity={}, product={}, limit={}, page={}, force={}",
            hostname, severity, product, limit, page, force
        )

        return try {
            // Validate hostname is not null or blank
            if (hostname.isNullOrBlank()) {
                throw IllegalArgumentException("Hostname or instance ID is required")
            }

            val trimmedInput = hostname.trim()

            // Validate limit
            val pageSize = when {
                limit == null -> 20000
                limit < 1 -> {
                    throw IllegalArgumentException("Limit must be greater than 0")
                }
                limit > 20000 -> {
                    throw IllegalArgumentException("Limit cannot exceed 20000")
                }
                else -> limit
            }

            val pageNumber = when {
                page == null -> 0
                page < 0 -> {
                    throw IllegalArgumentException("Page must be 0 or greater")
                }
                else -> page
            }

            // Detect query type and route to appropriate service method.
            //
            // force=true is the "Refresh" button on the System Vulnerabilities lookup: the operator
            // explicitly asked for live CrowdStrike data, so route to the uncached *Live methods
            // which bypass both the persisted rows and the vulnerability_queries cache. The plain
            // (non-force) path is unchanged: DB-first, 15-minute cache.
            val response: CrowdStrikeQueryResponse = if (InputDetectionUtils.isAwsInstanceId(trimmedInput)) {
                // Instance ID query (Feature 041)
                // T017: Validate instance ID format. Only the validation call belongs inside this
                // try — wrapping the service call too would misreport any IllegalArgumentException
                // raised by the service as an instance-ID *format* error.
                val normalizedInstanceId = try {
                    ValidationUtils.validateAndNormalizeAwsInstanceId(trimmedInput)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(ValidationUtils.getInstanceIdValidationError(trimmedInput))
                }
                log.info("Detected AWS instance ID query: instanceId={}, force={}", normalizedInstanceId, force)

                if (force) {
                    liveOrPersisted(
                        identifier = normalizedInstanceId,
                        live = { queryService.queryByInstanceIdLive(normalizedInstanceId, severity, product) },
                        persisted = {
                            queryService.queryFromDatabaseByInstance(
                                normalizedInstanceId, severity, product, pageSize, pageNumber
                            )
                        }
                    )
                } else {
                    queryService.queryByInstanceId(
                        instanceId = normalizedInstanceId,
                        severity = severity,
                        product = product,
                        limit = pageSize,
                        page = pageNumber
                    )
                }
            } else {
                // Hostname query (existing functionality)
                val sanitizedHostname = sanitizeHostname(trimmedInput)
                log.info("Detected hostname query: hostname={}, force={}", sanitizedHostname, force)

                if (force) {
                    liveOrPersisted(
                        identifier = sanitizedHostname,
                        live = { queryService.queryVulnerabilitiesLive(sanitizedHostname, severity, product) },
                        persisted = {
                            queryService.queryFromDatabase(
                                sanitizedHostname, severity, product, pageSize, pageNumber
                            )
                        }
                    )
                } else {
                    queryService.queryVulnerabilities(
                        hostname = sanitizedHostname,
                        severity = severity,
                        product = product,
                        limit = pageSize,
                        page = pageNumber
                    )
                }
            }

            log.info(
                "Query successful: input={}, severity={}, product={}, found={}",
                trimmedInput, severity, product, response.vulnerabilities.size
            )
            HttpResponse.ok(response)
        } catch (e: IllegalArgumentException) {
            // T017: Validation errors (including instance ID format errors)
            log.warn("Invalid query parameters: {}", e.message)
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid parameters")))
        } catch (e: CrowdStrikeError.NotFoundError) {
            // T018: Instance ID or hostname not found
            val identifier = hostname?.trim() ?: "unknown"
            val errorMessage = if (InputDetectionUtils.isAwsInstanceId(identifier)) {
                "System not found with instance ID: $identifier"
            } else {
                e.message
            }
            log.warn("System not found: {}", identifier)
            HttpResponse.notFound(mapOf("error" to errorMessage))
        } catch (e: CrowdStrikeError.RateLimitError) {
            log.warn("Rate limit exceeded")
            val retryAfter = e.retryAfterSeconds ?: 60
            HttpResponse.status<Map<String, String>>(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", retryAfter.toString())
                .body(mapOf("error" to "Rate limit exceeded. Please retry later."))
        } catch (e: CrowdStrikeError.ConfigurationError) {
            log.error("Configuration error: {}", e.message)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "CrowdStrike API is not configured properly"))
        } catch (e: CrowdStrikeError) {
            log.error("CrowdStrike error: {}", e.message)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "An error occurred communicating with CrowdStrike"))
        } catch (e: Exception) {
            log.error("Unexpected error", e)
            HttpResponse.serverError(mapOf("error" to "An unexpected error occurred"))
        }
    }

    /**
     * Legacy endpoint: Query CrowdStrike for system vulnerabilities
     *
     * @param hostname System hostname to query
     * @return CrowdStrikeQueryResponse with vulnerabilities
     */
    @Get("/crowdstrike/vulnerabilities")
    open fun queryVulnerabilitiesLegacy(@QueryValue @NotBlank hostname: String, authentication: Authentication): HttpResponse<*> =
        queryAuthorizedVulnerabilities(hostname, force = true, authentication = authentication)

    /**
     * Save CrowdStrike vulnerabilities to database with asset auto-creation
     *
     * Supports two modes:
     * 1. Single server (Feature 030): CrowdStrikeSaveRequest
     * 2. Batch import (Feature 032): List<CrowdStrikeVulnerabilityBatchDto>
     *
     * Features: 030-crowdstrike-asset-auto-creation, 032-servers-query-import
     *
     * @param request Save request (single or batch)
     * @param authentication Current authenticated user
     * @return Save response with statistics
     */
    @Post("/crowdstrike/vulnerabilities/save")
    @Secured("ADMIN")
    open fun saveVulnerabilities(
        @Body @Valid request: CrowdStrikeSaveRequest,
        authentication: Authentication  // T012: Pass authentication to service
    ): HttpResponse<*> {
        val username = authentication.name
        log.info("Received CrowdStrike save request: hostname={}, count={}, user={}", request.hostname, request.vulnerabilities.size, username)

        return try {
            val response = crowdStrikeService.saveToDatabase(request, authentication)  // T012: Pass both parameters

            // Saving mutates the persisted rows that the DB-first lookup path serves, so the
            // 15-minute vulnerability_queries cache is now stale for this host. Without this, the
            // natural Refresh -> "Save to Database" -> Search flow would show pre-save data for up
            // to 15 minutes. Same non-fatal pattern the import service already uses; a cache
            // failure must never fail an otherwise successful save.
            if (response.vulnerabilitiesSaved > 0) {
                try {
                    queryService.invalidateAllCachedQueries()
                } catch (e: Exception) {
                    log.warn("Failed to invalidate vulnerability_queries cache after save", e)
                }
            }

            log.info("CrowdStrike save successful: {}, user={}", response.message, username)
            HttpResponse.ok(response)
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid save request: user={}", username, e)
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            log.error("Database error saving vulnerabilities: user={}", username, e)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "An internal error occurred while saving vulnerabilities"))
        }
    }

    @Post("/crowdstrike/vulnerabilities/sync")
    @Secured("ADMIN")
    open fun synchronizeVulnerabilities(@Body @Valid request: com.secman.dto.CrowdStrikeSyncRequest,
        authentication: Authentication): HttpResponse<*> = try {
        HttpResponse.ok(syncService.sync(request, authentication))
    } catch (e: io.micronaut.http.exceptions.HttpStatusException) {
        HttpResponse.status<Map<String, String>>(e.status).body(mapOf("error" to e.message))
    } catch (e: com.secman.service.CrowdStrikeIdentityConflict) {
        log.warn("Falcon sync rejected: actor={} assetId={} code={} outcome=preserved",
            authentication.name.replace('\r', '_').replace('\n', '_'), e.assetId, e.code)
        HttpResponse.badRequest(mapOf("error" to e.message, "errorCode" to e.code,
            "assetId" to e.assetId, "formerInstanceId" to e.formerInstanceId, "instanceId" to e.instanceId))
    } catch (e: IllegalArgumentException) {
        log.warn("Falcon sync rejected: actor={} outcome=preserved", authentication.name.replace('\r', '_').replace('\n', '_'), e)
        HttpResponse.badRequest(mapOf("error" to "Falcon identity or snapshot could not be verified; stored data was preserved."))
    } catch (e: Exception) {
        log.error("Falcon sync failed: actor={} outcome=preserved", authentication.name.replace('\r', '_').replace('\n', '_'), e)
        HttpResponse.serverError(mapOf("error" to "Falcon synchronization failed; stored data was preserved."))
    }

    /**
     * Batch import server vulnerabilities from CrowdStrike
     *
     * @param batches List of server vulnerability batches
     * @param authentication Current authenticated user
     * @return ImportStatisticsDto with detailed statistics
     *
     * Security: Inherits controller-level @Secured("ADMIN", "VULN") - only ADMIN/VULN roles can import
     *
     * Error responses:
     * - 400: Invalid request (validation failure)
     * - 401: Unauthorized
     * - 403: Forbidden (requires ADMIN or VULN role)
     * - 500: Server error
     */
    @Post("/crowdstrike/servers/import")
    open fun importServerVulnerabilities(
        // The whole body is deserialized eagerly, and @ExecuteOn(BLOCKING) runs on virtual
        // threads — so without a cap the number of concurrent fully-parsed request bodies
        // pinned in heap is unbounded (a contributor to the 2026-07-30 import OOM).
        // The CLI posts 20 servers per request (VulnerabilityStorageService.batchSize);
        // 100 leaves room for a future bump while keeping the bound meaningful.
        // Per-server vulnerability count is already capped on the DTO itself.
        @Body @Valid @Size(max = 100) batches: List<CrowdStrikeVulnerabilityBatchDto>,
        authentication: Authentication,
        @QueryValue(defaultValue = "false") deleteOnFail: Boolean = false
    ): HttpResponse<*> {
        val username = authentication.name
        log.info("Received batch import request: servers={}, user={}", batches.size, username)

        return try {
            if (deleteOnFail && !authentication.roles.contains("ADMIN")) {
                return HttpResponse.status<Any>(HttpStatus.FORBIDDEN)
            }
            // T023: Call import service
            if (batches.any { it.deviceSelection != null } && !authentication.roles.contains("ADMIN")) {
                log.warn("Falcon device replacement denied: user={} outcome=forbidden", username)
                return HttpResponse.status<Map<String, String>>(HttpStatus.FORBIDDEN)
                    .body(mapOf("error" to "Falcon device replacement requires ADMIN"))
            }
            if (!authentication.roles.contains("ADMIN") && batches.any { batch ->
                val asset = batch.cloudInstanceId?.let(assetRepository::findByCloudInstanceIdIgnoreCase)
                    ?: assetRepository.findByCrowdStrikeHostnameIgnoreCase(batch.hostname)
                    ?: assetRepository.findByNameIgnoreCase(batch.hostname)
                asset?.id == null || !assetFilterService.canAccessAsset(requireNotNull(asset.id), authentication)
            }) return HttpResponse.notFound<Any>()
            val statistics = importService.importServerVulnerabilities(batches, username,
                allowDeviceReplacement = authentication.roles.contains("ADMIN"),
                recoverRejected = if (deleteOnFail) { batch, conflict ->
                    importRecoveryService.recover(batch, conflict, username)
                } else null)

            log.info("Batch import completed: servers processed={}, created={}, updated={}, vulnerabilities imported={}, skipped={}, errors={}, user={}",
                statistics.serversProcessed, statistics.serversCreated, statistics.serversUpdated,
                statistics.vulnerabilitiesImported, statistics.vulnerabilitiesSkipped, statistics.errors.size, username)

            // T023: Return statistics
            HttpResponse.ok(statistics).header("X-Secman-Deleteonfail", if (deleteOnFail) "atomic-v1" else "disabled")
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid batch import request: user={}", username, e)
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            val rootCause = generateSequence<Throwable>(e) { it.cause }.last()
            log.error("Error importing server vulnerabilities: user={}, exception={}, message={}, rootCause=[{}] {}",
                username, e.javaClass.name, e.message, rootCause.javaClass.simpleName, rootCause.message, e)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "An internal error occurred while importing server vulnerabilities"))
        }
    }

    /**
     * Reconcile stale CrowdStrike-import-owned vulnerabilities after a severity-filtered
     * import cycle completes.
     *
     * Closes the silent-remediation gap: the daily CLI cron runs with `--severity CRITICAL,HIGH`,
     * so a host whose findings have dropped below that threshold drops out of the import
     * payload and its existing HIGH/CRITICAL rows are never deleted by the per-host
     * delete-insert path. This endpoint, called by the CLI after streaming completes,
     * deletes the leftover rows in one statement using the run-start timestamp as the
     * staleness fence.
     *
     * Body: `{ importStartedAt, severities }`. Owner is implicitly `CROWDSTRIKE_IMPORT`.
     *
     * The sweep runs as a background job: on a large table it exceeds reverse-proxy
     * timeouts (real incident: ~65s vs nginx's 60s → 504 to the CLI while the backend
     * completed fine). Returns 202 + jobId immediately; the CLI polls
     * GET .../reconcile-stale/{jobId}/status until a terminal status.
     *
     * Security: inherits class-level `@Secured("ADMIN", "VULN")`.
     */
    @Post("/crowdstrike/servers/reconcile-stale")
    open fun reconcileStaleVulnerabilities(
        @Body @Valid request: ReconcileStaleVulnerabilitiesRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        val username = authentication.name
        // Log both client- and server-side clocks so any future timezone skew is
        // visible from a single line. The service-layer guard rejects future
        // cutoffs (see CrowdStrikeVulnerabilityImportService.reconcileStaleCrowdStrikeImports),
        // but logging the raw pair here makes the diagnosis instant.
        log.info(
            "Reconcile stale CrowdStrike vulns: importStartedAt(client)={}, backendNow={}, severities={}, user={}",
            request.importStartedAt, java.time.LocalDateTime.now(), request.severities, username
        )
        // Pre-validate here (mirrors the service's require()) so a bad request is a 400
        // to the caller instead of a job that only fails after polling.
        if (request.severities.none { it.isNotBlank() }) {
            return HttpResponse.badRequest(mapOf("error" to "severities must contain at least one non-blank value"))
        }
        return try {
            val started = reconcileJobService.startReconcile(username, request)
            HttpResponse.status<ReconcileJobStartedResponse>(HttpStatus.ACCEPTED).body(started)
        } catch (e: ReconcileJobConflictException) {
            log.warn("Reconcile request rejected, job {} already running: user={}", e.existingJobId, username)
            HttpResponse.status<Map<String, String>>(HttpStatus.CONFLICT)
                .body(mapOf("error" to "A reconcile job is already running", "jobId" to e.existingJobId))
        } catch (e: Exception) {
            log.error("Error starting reconcile job: user={}", username, e)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "An internal error occurred while starting the reconcile job"))
        }
    }

    /**
     * Poll the status of a reconcile-stale background job started via the POST above.
     *
     * Returns 404 when the job doesn't exist or belongs to another user. `result` is
     * populated only on COMPLETED; an aborted-by-safety-brake sweep is COMPLETED with
     * `result.aborted = true`, not FAILED.
     *
     * Security: inherits class-level `@Secured("ADMIN", "VULN")`.
     */
    @Get("/crowdstrike/servers/reconcile-stale/{jobId}/status")
    open fun getReconcileJobStatus(
        @PathVariable jobId: String,
        authentication: Authentication
    ): HttpResponse<*> {
        val status = reconcileJobService.getJobStatus(jobId, authentication.name)
        return status?.let { HttpResponse.ok(it) }
            ?: HttpResponse.status<Map<String, String>>(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "Reconcile job not found"))
    }

    /**
     * Retrieve metadata for the most recent CrowdStrike import.
     *
     * Allows UI to display data freshness indicators.
     */
    @Get("/crowdstrike/servers/import/latest")
    open fun getLatestImportStatus(): HttpResponse<*> {
        val status: CrowdStrikeImportStatusDto? = importService.getLatestImportStatus()
        return status?.let { HttpResponse.ok(it) } ?: HttpResponse.noContent<CrowdStrikeImportStatusDto>()
    }

    /**
     * Public endpoint: returns the timestamp of the most recent CrowdStrike
     * checkin (i.e. the latest successful CrowdStrike import) as an ISO-8601
     * string, or the literal string "never" if no import has ever occurred.
     *
     * Unauthenticated by design so that external monitoring / dashboards can
     * poll the freshness of CrowdStrike data without needing API credentials.
     * Only a single timestamp (or "never") is exposed — no other information.
     */
    @Get("/crowdstrike/last-checkin", produces = [MediaType.TEXT_PLAIN])
    @Secured(SecurityRule.IS_ANONYMOUS)
    open fun getLastCheckin(): HttpResponse<String> {
        val status = importService.getLatestImportStatus()
        val body = status?.importedAt?.toString() ?: "never"
        return HttpResponse.ok(body)
    }

    /**
     * Sanitize and validate hostname
     *
     * @param hostname Raw hostname input
     * @return Sanitized hostname
     * @throws IllegalArgumentException if hostname format is invalid
     */
    private fun sanitizeHostname(hostname: String): String {
        val trimmed = hostname.trim()

        val hostnameRegex = Regex("^[a-zA-Z0-9.-]+$")
        if (!hostnameRegex.matches(trimmed)) {
            throw IllegalArgumentException("Invalid hostname format. Only alphanumeric characters, dots, and hyphens are allowed.")
        }

        if (trimmed.contains("--") || trimmed.contains("..") || trimmed.startsWith("-") || trimmed.endsWith("-")) {
            throw IllegalArgumentException("Invalid hostname format.")
        }

        return trimmed
    }

    /**
     * Run a force-refresh live CrowdStrike lookup, falling back to the persisted rows when Falcon
     * has no device record for the identifier.
     *
     * A `NotFoundError` on the live path does NOT mean "no vulnerability data" — it means Falcon
     * holds no device for this hostname/instance ID, which routinely happens for decommissioned
     * hosts, uninstalled sensors and renamed machines. Failing the request outright would clear the
     * operator's results (the frontend drops `queryResponse` on error), so a screen full of valid
     * historic data would vanish because the user pressed Refresh. Instead the stored rows are
     * returned with a `notice` explaining that the live lookup found nothing.
     *
     * Deliberately narrow: only `NotFoundError` is caught. `RateLimitError` and `ServerError` are
     * transient and must keep surfacing as 429/500 so the operator retries rather than believing
     * they are looking at freshly refreshed data.
     */
    private fun liveOrPersisted(
        identifier: String,
        live: () -> CrowdStrikeQueryResponse,
        persisted: () -> CrowdStrikeQueryResponse?
    ): CrowdStrikeQueryResponse {
        return try {
            live()
        } catch (e: CrowdStrikeError.NotFoundError) {
            val fallback = persisted()
            if (fallback == null) {
                // Unknown to Falcon *and* nothing stored - a genuine 404.
                throw e
            }
            log.warn(
                "Live refresh found no CrowdStrike device for {} - serving {} persisted vulnerabilities instead",
                identifier, fallback.vulnerabilities.size
            )
            fallback.copy(
                notice = "Not found in CrowdStrike Falcon - showing the last imported data instead. " +
                    "The host may have been decommissioned, renamed, or had its sensor removed."
            )
        }
    }
}
