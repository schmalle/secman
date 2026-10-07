package com.secman.crowdstrike.client

import com.secman.crowdstrike.auth.CrowdStrikeAuthService
import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto
import com.secman.crowdstrike.dto.DeviceType
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.dto.InstalledProductDto
import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.crowdstrike.dto.selectLatestCrowdStrikeDevices
import com.secman.crowdstrike.exception.CrowdStrikeException
import com.secman.crowdstrike.exception.NotFoundException
import com.secman.crowdstrike.exception.RateLimitException
import com.secman.crowdstrike.model.AuthToken
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientException
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.uri.UriBuilder
import io.micronaut.retry.annotation.Retryable
import io.netty.channel.ChannelException
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import com.secman.crowdstrike.FalconTimestamps
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * CrowdStrike Falcon API client implementation
 *
 * Provides:
 * - Spotlight API queries for vulnerability data
 * - Automatic retry with exponential backoff for transient errors
 * - Pagination support for large result sets
 * - Response mapping to shared DTOs
 */
@Singleton
open class CrowdStrikeApiClientImpl(
    @param:Client("https://api.crowdstrike.com")
    private val httpClient: HttpClient,
    private val authService: CrowdStrikeAuthService
) : CrowdStrikeApiClient {

    private val log = LoggerFactory.getLogger(CrowdStrikeApiClientImpl::class.java)

    @field:Value("\${secman.crowdstrike.batch-size:20}")
    protected var configuredBatchSize: Int = 20

    @field:Value("\${secman.crowdstrike.max-parallel-batches:10}")
    protected var configuredMaxParallelBatches: Int = 10

    /**
     * Query vulnerabilities for a specific hostname
     *
     * @param hostname System hostname to query
     * @param config CrowdStrike configuration
     * @return CrowdStrikeQueryResponse with vulnerabilities
     * @throws NotFoundException if hostname not found in CrowdStrike
     * @throws RateLimitException if rate limit exceeded
     * @throws CrowdStrikeException for other API errors
     */
    override fun queryVulnerabilities(hostname: String, config: FalconConfigDto): CrowdStrikeQueryResponse {
        require(hostname.isNotBlank()) { "Hostname cannot be blank" }

        log.info("Querying CrowdStrike for vulnerabilities: hostname={}", hostname)

        return try {
            // Authenticate
            val token = getAuthToken(config)

            val discoveredIds = getDeviceIdsByHostname(hostname, token)
            if (discoveredIds.isEmpty()) throw NotFoundException("Hostname not found in CrowdStrike: $hostname")
            val metadata = selectImportDevices(discoveredIds, resolveImportMetadata(discoveredIds, config))
            val matching = metadata.filterValues { md ->
                md.hostname.equals(hostname, ignoreCase = true) ||
                    (!hostname.contains('.') && md.hostname?.substringBefore(".").equals(hostname, ignoreCase = true))
            }
            if (matching.size != 1) throw CrowdStrikeException("Hostname does not resolve to one unambiguous Falcon device")
            val deviceIds = matching.keys.toList()
            log.info("Selected latest device for hostname={}: aid={}, ignored={}", hostname,
                deviceIds.single(), matching.values.single().selection?.superseded?.size ?: 0)

            val vulnerabilityDtos = mutableListOf<CrowdStrikeVulnerabilityDto>()
            val failedAids = mutableSetOf<String>()
            failedAids.addAll(deviceIds.filter { metadata[it]?.hostname.isNullOrBlank() && metadata[it]?.cloudInstanceId.isNullOrBlank() })
            deviceIds.forEach { deviceId ->
                try {
                    // Query Spotlight API - pass hostname so it can be included in results
                    vulnerabilityDtos.addAll(querySpotlightApi(deviceId, hostname, token))
                } catch (e: Exception) {
                    failedAids.add(deviceId)
                    log.warn("Failed to query vulnerabilities for device {} of hostname '{}': {}",
                        deviceId, hostname, e.message)
                }
            }
            // Spotlight vulnerability ids are unique per aid; dedupe by id is
            // belt-and-braces against overlapping resolutions.
            val merged = vulnerabilityDtos.distinctBy { it.id }

            log.info("CrowdStrike lookup completed: hostname={}, devices={}, count={}, failedDevices={}",
                hostname, deviceIds.size, merged.size, failedAids.size)

            CrowdStrikeQueryResponse(
                hostname = hostname,
                vulnerabilities = merged,
                totalCount = merged.size,
                deviceCount = deviceIds.size,
                failedAids = failedAids,
                devices = deviceIds.map { id -> metadata[id]?.toQueriedHost(id) ?: QueriedHost(hostname, null, id) }.toSet(),
                queriedAt = LocalDateTime.now()
            )
        } catch (e: CrowdStrikeException) {
            log.error("CrowdStrike query failed: hostname={}, error={}", hostname, e.message)
            throw e
        } catch (e: Exception) {
            log.error("Unexpected error querying CrowdStrike: hostname={}", hostname, e)
            throw CrowdStrikeException("Failed to query vulnerabilities for $hostname: ${e.message}", e)
        }
    }

    /**
     * Query all vulnerabilities for a single hostname.
     *
     * The CrowdStrike Spotlight per-host endpoint returns every vulnerability for the device
     * in one response, so there is no page-size knob to honor. This method is a thin wrapper
     * over [queryVulnerabilities] kept for callers that historically expected a paginating
     * variant; the wrapper now exists for API stability only.
     */
    override fun queryAllVulnerabilities(
        hostname: String,
        config: FalconConfigDto
    ): CrowdStrikeQueryResponse {
        return queryVulnerabilities(hostname, config)
    }

    /**
     * Query the selected current devices using bounded Spotlight batches.
     *
     * @param severity Severity filter (e.g., "HIGH,CRITICAL")
     * @param minDaysOpen Minimum days open filter (e.g., 30)
     * @param deviceType Device type filter (e.g., "SERVER")
     * @param config CrowdStrike Falcon configuration
     * @param limit Page size for pagination
     * @return CrowdStrikeQueryResponse with filtered vulnerabilities
     */
    @Retryable(
        includes = [RateLimitException::class],
        attempts = "5",
        delay = "1s",
        multiplier = "2.0",
        maxDelay = "60s"
    )
    open fun queryAllVulnerabilitiesBulk(
        severity: String,
        minDaysOpen: Int,
        deviceType: String,
        config: FalconConfigDto,
        limit: Int = 1000  // Reduced from 5000 for better stability
    ): CrowdStrikeQueryResponse {
        return queryServersWithFilters(null, deviceType, severity, minDaysOpen, config, limit)
    }

    /**
     * Query servers with filters (device type, severity, days open)
     *
     * @param hostnames Optional list of specific hostnames to query (null = all servers)
     * @param deviceType Device type filter (e.g., "SERVER")
     * @param severity Severity filter (e.g., "HIGH,CRITICAL")
     * @param minDaysOpen Minimum days open filter (e.g., 30)
     * @param config CrowdStrike Falcon configuration
     * @param limit Page size for pagination
     * @return CrowdStrikeQueryResponse with filtered vulnerabilities
     */
    override fun queryServersWithFilters(
        hostnames: List<String>?,
        deviceType: String,
        severity: String,
        minDaysOpen: Int,
        config: FalconConfigDto,
        limit: Int,
        lastSeenDays: Int
    ): CrowdStrikeQueryResponse {
        log.info("Querying CrowdStrike servers: hostnames={}, deviceType={}, severity={}, minDaysOpen={}",
            hostnames?.joinToString(",") ?: "ALL", deviceType, severity, minDaysOpen)

        val allVulnerabilities = mutableListOf<CrowdStrikeVulnerabilityDto>()
        val notFoundHostnames = mutableListOf<String>()
        val failedAids = mutableSetOf<String>()
        val queriedDevices = mutableSetOf<QueriedHost>()

        // If specific hostnames provided, query each one
        if (!hostnames.isNullOrEmpty()) {
            if (lastSeenDays > 0 || DeviceType.fromString(deviceType) != DeviceType.SERVER) {
                // Deliberately NOT honored here: the user named the hosts explicitly, and
                // filtering them out server-side by last_seen or product type would
                // silently reproduce the "0 rows, exit 0" ambiguity this path is meant
                // to avoid. Severity/minDaysOpen ARE applied — client-side, below; the
                // per-host FQL stays `aid+status:'open'` because querySpotlightApi is
                // shared with the instance-id flow, where no severity restriction is
                // wanted, and per-host row counts are small.
                log.warn("--device-type and --last-seen-days are ignored for hostname-specific queries " +
                    "(hostnames are resolved directly, without device filters)")
            }
            hostnames.forEach { hostname ->
                try {
                    val response = queryVulnerabilities(hostname, config)
                    failedAids.addAll(response.failedAids)
                    queriedDevices.addAll(response.devices)
                    // Filter by severity and days open
                    val filtered = response.vulnerabilities.filter { vuln ->
                        val severityMatches = severity.split(",").any {
                            it.trim().equals(vuln.severity, ignoreCase = true)
                        }
                        // Parse daysOpen from string (e.g., "15 days" -> 15)
                        val daysOpenValue = vuln.daysOpen?.split(" ")?.firstOrNull()?.toIntOrNull() ?: 0
                        val daysOpenMatches = daysOpenValue >= minDaysOpen
                        severityMatches && daysOpenMatches
                    }
                    allVulnerabilities.addAll(filtered)
                    log.debug("Hostname '{}': found {} vulnerabilities ({} after filtering)",
                        hostname, response.vulnerabilities.size, filtered.size)
                } catch (e: NotFoundException) {
                    // Recorded, not just logged: the caller must be able to distinguish
                    // "unknown to Falcon" from "resolved but no matching rows".
                    notFoundHostnames.add(hostname)
                    log.warn("Hostname '{}' not found in CrowdStrike, skipping", hostname)
                } catch (e: Exception) {
                    log.error("Error querying hostname '{}'", hostname, e)
                    throw e
                }
            }
        } else {
            // TWO-STAGE OPTIMIZED QUERY:
            // 1. Query devices first using the selected product_type_desc scope
            // 2. Query vulnerabilities only for those specific devices
            // This avoids querying ALL vulnerabilities (which caused 30-minute timeouts)
            val parsedDeviceType = DeviceType.fromString(deviceType)
            log.info("Querying all {} using two-stage optimization (devices first, then vulnerabilities)", parsedDeviceType.displayName())

            // Stage 1: Get device IDs for the specified type
            log.info(">>> Stage 1: Getting authentication token")
            val token = getAuthToken(config)
            log.info(">>> Stage 1: Querying {} devices with product_type_desc filter", parsedDeviceType.name)
            val discoveredIds = getDeviceIdsFiltered(token, parsedDeviceType, limit, 0)
            val selectedMetadata = selectImportDevices(discoveredIds, resolveImportMetadata(discoveredIds, config), lastSeenDays)
            val serverDeviceIds = selectedMetadata.keys.toList()

            if (serverDeviceIds.isEmpty()) {
                log.info(">>> Stage 1: No {} devices found in CrowdStrike", parsedDeviceType.name)
                return CrowdStrikeQueryResponse(
                    hostname = "ALL",
                    vulnerabilities = emptyList(),
                    totalCount = 0,
                    queriedAt = LocalDateTime.now()
                )
            }

            log.info(">>> Stage 1 complete: Found {} {} devices", serverDeviceIds.size, parsedDeviceType.name)
            log.info(">>> Stage 1: Sample device IDs: {}", serverDeviceIds.take(10).joinToString(", "))

            // Stage 2: Query vulnerabilities for those specific devices
            log.info(">>> Stage 2: Starting vulnerability query for {} devices", serverDeviceIds.size)
            val outcome = queryVulnerabilitiesWithMetadata(serverDeviceIds, severity, minDaysOpen,
                config, limit, selectedMetadata)
            allVulnerabilities.addAll(outcome.vulnerabilities)
            failedAids.addAll(outcome.failedDeviceIds)
            queriedDevices.addAll(selectedMetadata.map { (id, md) -> md.toQueriedHost(id) })

            log.info("Stage 2 complete: {} vulnerabilities found across {} servers",
                outcome.vulnerabilities.size, serverDeviceIds.size)
        }

        return CrowdStrikeQueryResponse(
            hostname = hostnames?.joinToString(",") ?: "ALL",
            vulnerabilities = allVulnerabilities,
            totalCount = allVulnerabilities.size,
            notFoundHostnames = notFoundHostnames,
            failedAids = failedAids,
            devices = queriedDevices,
            queriedAt = LocalDateTime.now()
        )
    }

    /**
     * Streaming variant of queryServersWithFilters that processes device batches incrementally.
     *
     * Reduces peak memory by not accumulating all vulnerabilities in a single list.
     * Device IDs are queried first, then chunked into groups of deviceBatchSize.
     * Each chunk's vulnerabilities are queried and passed to the batchProcessor callback,
     * allowing the caller to import/process and discard before the next chunk loads.
     *
     * @return Total number of vulnerabilities processed
     */
    override fun queryServersWithFiltersStreaming(
        deviceType: String,
        severity: String,
        minDaysOpen: Int,
        config: FalconConfigDto,
        limit: Int,
        lastSeenDays: Int,
        deviceBatchSize: Int,
        batchProcessor: (StreamingVulnerabilityBatch) -> Unit
    ): StreamingImportResult {
        val parsedDeviceType = DeviceType.fromString(deviceType)
        log.info("Streaming query for {} devices: severity={}, minDaysOpen={}", parsedDeviceType.name, severity, minDaysOpen)

        // Stage 1: Get all device IDs
        val token = getAuthToken(config)
        var serverDeviceIds = getDeviceIdsFiltered(token, parsedDeviceType, limit, 0)

        if (serverDeviceIds.isEmpty()) {
            log.info("No {} devices found in CrowdStrike", parsedDeviceType.name)
            return StreamingImportResult(totalVulnerabilities = 0, queriedHosts = emptySet())
        }

        // Resolve the FULL queried device population (hostname + instance id) up front —
        // including devices that will return zero matching vulnerabilities. The backend
        // scopes its stale-reconcile sweep to assets resolved from this set, so a host
        // outside the --last-seen-days window (not in serverDeviceIds) is never swept, and
        // a fully-remediated host that returns no vulns this run is still cleaned up.
        // Metadata must resolve before replacement; an unknown AID may be a sibling.
        // The deviceId keys are kept so failed Stage-2 batches can be mapped back to their
        // QueriedHosts and reported in failedHosts.
        val metadataStarted = System.nanoTime()
        var metadataByDeviceId = resolveImportMetadata(serverDeviceIds, config)
        if (serverDeviceIds.any { it !in metadataByDeviceId }) {
            // Without metadata an unresolved AID could be a sibling of any resolved host.
            throw CrowdStrikeException("Incomplete device metadata; import stopped before replacement")
        }
        val namelessDevices = serverDeviceIds.filter {
            metadataByDeviceId[it]?.hostname.isNullOrBlank() && metadataByDeviceId[it]?.cloudInstanceId.isNullOrBlank()
        }
        // Falcon can return nameless devices. Exclude them only after proving they
        // contribute no open findings to a sibling host's replacement payload.
        namelessDevices.forEach { deviceId ->
            if (!hasNoOpenFindings(deviceId, getAuthToken(config))) {
                throw CrowdStrikeException("Incomplete device metadata; import stopped before replacement")
            }
        }
        if (namelessDevices.isNotEmpty()) {
            serverDeviceIds = serverDeviceIds - namelessDevices.toSet()
            log.warn("Excluded {} nameless Falcon devices with confirmed zero open findings from import and reconciliation",
                namelessDevices.size)
        }
        metadataByDeviceId = selectImportDevices(serverDeviceIds, metadataByDeviceId, lastSeenDays)
        serverDeviceIds = metadataByDeviceId.keys.toList()
        log.info("Import metadata completed: devices={}, resolved={}, durationMs={}",
            serverDeviceIds.size, metadataByDeviceId.size, (System.nanoTime() - metadataStarted) / 1_000_000)
        fun toQueriedHost(deviceId: String, md: DeviceMetadata): QueriedHost? {
            val h = md.hostname?.trim()?.takeIf { it.isNotBlank() }
            val i = md.cloudInstanceId?.trim()?.takeIf { it.isNotBlank() }
            return if (h == null && i == null) null else md.toQueriedHost(deviceId)
        }
        val queriedHosts = metadataByDeviceId.mapNotNull { (deviceId, metadata) ->
            toQueriedHost(deviceId, metadata)
        }.toSet()
        log.info("Resolved {} device identities from {} device id(s) for reconcile scoping",
            queriedHosts.size, serverDeviceIds.size)

        log.info("Found {} {} devices, processing in batches of {}", serverDeviceIds.size, parsedDeviceType.name, deviceBatchSize)

        // Selection has already reduced each host/instance group to one AID.
        val deviceChunks = serverDeviceIds.chunked(deviceBatchSize)
        var totalVulnerabilities = 0
        val failedDeviceIds = (serverDeviceIds - metadataByDeviceId.keys).toMutableSet()

        deviceChunks.forEachIndexed { index, chunk ->
            log.info("Streaming batch {}/{}: querying vulnerabilities for {} devices",
                index + 1, deviceChunks.size, chunk.size)

            val fetchStarted = System.nanoTime()
            val outcome = queryVulnerabilitiesWithMetadata(
                deviceIds = chunk,
                severity = severity,
                minDaysOpen = minDaysOpen,
                config = config,
                limit = limit,
                metadataByDeviceId = metadataByDeviceId
            )
            failedDeviceIds.addAll(outcome.failedDeviceIds)
            val batchVulns = outcome.vulnerabilities
            log.info("Import fetch batch {}/{}: devices={}, rows={}, failedDevices={}, durationMs={}",
                index + 1, deviceChunks.size, chunk.size, batchVulns.size, outcome.failedDeviceIds.size,
                (System.nanoTime() - fetchStarted) / 1_000_000)

            if (chunk.any { it !in outcome.failedDeviceIds }) {
                totalVulnerabilities += batchVulns.size
                batchProcessor(StreamingVulnerabilityBatch(batchVulns, chunk.filterNot { it in outcome.failedDeviceIds }
                    .mapNotNull { id -> metadataByDeviceId[id]?.toQueriedHost(id) }.toSet()))
                log.info("Streaming batch {}/{}: processed {} vulnerabilities (total: {})",
                    index + 1, deviceChunks.size, batchVulns.size, totalVulnerabilities)
            }
        }

        val failedHosts = failedDeviceIds
            .mapNotNull { deviceId -> metadataByDeviceId[deviceId]?.let { toQueriedHost(deviceId, it) } }
            .toSet()

        if (failedHosts.isNotEmpty()) {
            log.warn("Streaming query completed with {} host(s) in failed/truncated batches — " +
                "these are excluded from reconcile scope", failedHosts.size)
        }
        log.info("Streaming query completed: {} total vulnerabilities across {} batches",
            totalVulnerabilities, deviceChunks.size)

        return StreamingImportResult(
            totalVulnerabilities = totalVulnerabilities,
            queriedHosts = queriedHosts,
            failedHosts = failedHosts,
            failedDeviceCount = failedDeviceIds.size
        )
    }


    override fun queryInstalledProductsStreaming(
        deviceType: String,
        config: FalconConfigDto,
        limit: Int,
        batchProcessor: (List<InstalledProductDto>) -> Unit
    ): Int {
        val parsedDeviceType = DeviceType.fromString(deviceType)
        val token = getAuthToken(config)
        val filters = parsedDeviceType.atomicTypes().map { atomicType ->
            "host.${requireNotNull(atomicType.toFqlFilter())}"
        }

        var totalProcessed = 0
        filters.forEach { filter ->
            totalProcessed += queryInstalledProductsForFilter(
                token = token,
                config = config,
                filter = filter,
                limit = limit.coerceIn(1, 1000),
                batchProcessor = batchProcessor
            )
        }
        return totalProcessed
    }

    @Retryable(
        includes = [RateLimitException::class],
        attempts = "5",
        delay = "1s",
        multiplier = "2.0",
        maxDelay = "60s"
    )
    open fun queryInstalledProductsForFilter(
        token: AuthToken,
        config: FalconConfigDto,
        filter: String,
        limit: Int,
        batchProcessor: (List<InstalledProductDto>) -> Unit
    ): Int {
        log.info("Querying CrowdStrike installed products with filter: {}", filter)
        var afterToken: String? = null
        var totalProcessed = 0
        var filterProcessed = 0
        var hasMore = true
        var page = 0
        val maxRetries = 3
        val retryCounts = mutableMapOf<Int, Int>()
        var currentToken = token

        fun retryInstalledProductsPage(pageNumber: Int, errorType: String, e: Exception): Boolean {
            val currentRetries = retryCounts.getOrDefault(pageNumber, 0)
            if (currentRetries >= maxRetries) {
                log.error(
                    "Installed products page {}: {} - {}. Max retries ({}) exceeded",
                    pageNumber,
                    errorType,
                    e.message,
                    maxRetries
                )
                return false
            }

            retryCounts[pageNumber] = currentRetries + 1
            val backoffMs = (currentRetries + 1) * 2000L
            log.warn(
                "Installed products page {}: {} - {}. Retry {}/{} after {}ms",
                pageNumber,
                errorType,
                e.message,
                currentRetries + 1,
                maxRetries,
                backoffMs
            )
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            return true
        }

        // A 404 only legitimately means "no data" on the very first request. Once we hold an
        // `after` cursor, CrowdStrike has promised more rows, so a 404 there is a transient
        // error to retry — never a silent end-of-pagination (which would truncate the import
        // and report a false success). Returns true when the caller should retry (continue).
        fun handle404(currentPage: Int): Boolean {
            if (afterToken.isNullOrBlank()) {
                hasMore = false
                return false
            }
            val cause = CrowdStrikeException("CrowdStrike returned 404 with an active pagination cursor")
            if (retryInstalledProductsPage(currentPage, "Unexpected 404 mid-pagination", cause)) {
                return true
            }
            throw CrowdStrikeException(
                "Installed products pagination stopped early at page $currentPage: 404 after $filterProcessed rows with an active cursor (incomplete import)"
            )
        }

        while (hasMore) {
            val currentPage = page + 1
            try {
                if (currentToken.isExpiringSoon(bufferSeconds = 180)) {
                    log.info("CrowdStrike token expiring soon during installed products import, refreshing before page {}", currentPage)
                    currentToken = getAuthToken(config)
                }

                val uri = UriBuilder.of("/discover/combined/applications/v1")
                    .queryParam("filter", filter)
                    // install_usage is NOT optional enrichment: installation_paths,
                    // installation_timestamp and last_used_timestamp are ONLY returned when it is
                    // requested. Without it CrowdStrike omits the fields entirely and
                    // InstalledProduct.installationPath / installedAt / lastUsedAt persist as NULL
                    // for every row (verified 2026-08-19: 182131/182131 rows NULL).
                    .queryParam("facet", "host_info", "install_usage")
                    .queryParam("limit", limit)
                    .apply {
                        if (!afterToken.isNullOrBlank()) {
                            queryParam("after", afterToken)
                        }
                    }
                    .build()

                val request = HttpRequest.GET<Any>(uri.toString())
                    .header("Authorization", "Bearer ${currentToken.accessToken}")
                    .header("Accept", "application/json")

                val response = httpClient.toBlocking().exchange(request, Map::class.java)
                page = currentPage
                when (response.status.code) {
                    200 -> {
                        @Suppress("UNCHECKED_CAST")
                        val responseBody = response.body() as? Map<String, Any>
                            ?: throw CrowdStrikeException("Empty response from CrowdStrike Discover applications API")
                        val resources = responseBody["resources"] as? List<*> ?: emptyList<Any>()
                        val products = mapInstalledProducts(resources)
                        if (products.isNotEmpty()) {
                            batchProcessor(products)
                            totalProcessed += products.size
                            filterProcessed += products.size
                        }

                        val meta = responseBody["meta"] as? Map<*, *>
                        val pagination = meta?.get("pagination") as? Map<*, *>
                        afterToken = firstNonBlank(
                            pagination?.get("after")?.toString(),
                            pagination?.get("next")?.toString(),
                            responseBody["after"]?.toString()
                        )
                        val total = (pagination?.get("total") as? Number)?.toInt()
                        hasMore = !afterToken.isNullOrBlank() && resources.isNotEmpty() && (total == null || filterProcessed < total)
                        log.info("Installed products page {} returned {} rows (total processed: {})", currentPage, products.size, totalProcessed)
                    }
                    404 -> if (handle404(currentPage)) continue
                    429 -> {
                        val retryAfter = response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                        throw RateLimitException("Rate limit exceeded querying installed products", retryAfter)
                    }
                    in 500..599 -> throw CrowdStrikeException("CrowdStrike server error querying installed products: ${response.status}")
                    else -> throw CrowdStrikeException("Unexpected CrowdStrike installed products response: ${response.status}")
                }
            } catch (e: HttpClientResponseException) {
                when (e.status.code) {
                    401 -> {
                        log.warn("Unauthorized querying installed products page {}, refreshing CrowdStrike token and retrying", currentPage)
                        authService.clearCache()
                        currentToken = getAuthToken(config)
                        continue
                    }
                    404 -> if (handle404(currentPage)) continue
                    429 -> {
                        val retryAfter = e.response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                        throw RateLimitException("Rate limit exceeded querying installed products", retryAfter, e)
                    }
                    in 500..599 -> throw CrowdStrikeException("CrowdStrike server error querying installed products: ${e.status}", e)
                    else -> throw CrowdStrikeException("CrowdStrike installed products API error: ${e.message}", e)
                }
            } catch (e: SocketTimeoutException) {
                if (retryInstalledProductsPage(currentPage, "Timeout", e)) {
                    continue
                }
                throw CrowdStrikeException("Timeout querying installed products page $currentPage: ${e.message}", e)
            } catch (e: IOException) {
                if (retryInstalledProductsPage(currentPage, "Network I/O error", e)) {
                    continue
                }
                throw CrowdStrikeException("Network error querying installed products page $currentPage: ${e.message}", e)
            } catch (e: ChannelException) {
                if (retryInstalledProductsPage(currentPage, "Channel error", e)) {
                    continue
                }
                throw CrowdStrikeException("Channel error querying installed products page $currentPage: ${e.message}", e)
            } catch (e: HttpClientException) {
                val message = e.message.orEmpty()
                // A read-phase network failure (e.g. "Can't assign requested address" /
                // EADDRNOTAVAIL under sustained paging) surfaces as an HttpClientException
                // wrapping an IOException, so it never reaches the IOException arm above.
                // Walk the cause chain so any such transient I/O failure is retried instead
                // of aborting the whole multi-page run.
                val hasIoCause = generateSequence(e.cause) { it.cause }.any { it is IOException }
                val isTransient = hasIoCause ||
                    message.contains("Connection closed", ignoreCase = true) ||
                    message.contains("Channel closed", ignoreCase = true) ||
                    message.contains("closed before response", ignoreCase = true) ||
                    message.contains("reading HTTP response", ignoreCase = true) ||
                    message.contains("aggregating", ignoreCase = true)
                if (isTransient && retryInstalledProductsPage(currentPage, "HTTP client error", e)) {
                    continue
                }
                throw CrowdStrikeException("HTTP client error querying installed products page $currentPage: ${e.message}", e)
            }
        }

        return totalProcessed
    }

    private fun mapInstalledProducts(resources: List<*>): List<InstalledProductDto> {
        return resources.mapNotNull { resource ->
            val app = resource as? Map<*, *> ?: return@mapNotNull null
            val host = firstMap(app["host"], app["host_info"], app["hostInfo"])
            val nestedHost = firstMap(host?.get("host"), app["asset"])
            val hostname = firstNonBlank(
                host?.get("hostname")?.toString(),
                host?.get("host_name")?.toString(),
                nestedHost?.get("hostname")?.toString(),
                nestedHost?.get("host_name")?.toString(),
                app["hostname"]?.toString(),
                app["host_name"]?.toString()
            ) ?: return@mapNotNull null
            val name = firstNonBlank(
                app["name"]?.toString(),
                app["product_name"]?.toString(),
                app["application_name"]?.toString(),
                app["software_name"]?.toString()
            ) ?: return@mapNotNull null

            InstalledProductDto(
                externalId = app["id"]?.toString(),
                hostname = hostname,
                aid = firstNonBlank(
                    host?.get("aid")?.toString(),
                    host?.get("device_id")?.toString(),
                    nestedHost?.get("aid")?.toString(),
                    nestedHost?.get("device_id")?.toString(),
                    app["aid"]?.toString()
                ),
                name = name,
                vendor = firstNonBlank(app["vendor"]?.toString(), app["publisher"]?.toString()),
                version = firstNonBlank(app["version"]?.toString(), app["product_version"]?.toString()),
                category = app["category"]?.toString(),
                installationPath = firstNonBlank(
                    (app["installation_paths"] as? List<*>)?.firstOrNull()?.toString(),
                    app["installation_path"]?.toString()
                ),
                installedAt = parseCrowdStrikeDate(app["installation_timestamp"]?.toString()),
                lastUsedAt = parseCrowdStrikeDate(app["last_used_timestamp"]?.toString()),
                lastUpdatedAt = parseCrowdStrikeDate(app["last_updated_timestamp"]?.toString())
            )
        }
    }

    private fun firstMap(vararg values: Any?): Map<*, *>? {
        return values.firstNotNullOfOrNull { it as? Map<*, *> }
    }

    /**
     * Installed-product timestamps. Delegates to [FalconTimestamps] like every other
     * Falcon date — its old fallback stripped a trailing "Z" and reparsed the value as
     * a local time, relabelling the instant instead of converting it.
     */
    private fun parseCrowdStrikeDate(value: String?): LocalDateTime? = FalconTimestamps.parse(value)

    /**
     * Streaming summary: processes device batches incrementally but only retains
     * hostname → vulnerability count statistics. Reduces memory from O(all_vulns)
     * to O(batch_vulns) + O(host_count_map).
     */
    override fun queryServersWithFiltersSummary(
        deviceType: String,
        severity: String,
        minDaysOpen: Int,
        config: FalconConfigDto,
        limit: Int,
        lastSeenDays: Int,
        deviceBatchSize: Int,
        overdueThreshold: Int
    ): StreamingSummary {
        val parsedDeviceType = DeviceType.fromString(deviceType)
        log.info("Streaming summary query for {} devices: severity={}, minDaysOpen={}", parsedDeviceType.name, severity, minDaysOpen)

        val token = getAuthToken(config)
        val serverDeviceIds = getDeviceIdsFiltered(token, parsedDeviceType, limit, lastSeenDays)

        if (serverDeviceIds.isEmpty()) {
            log.info("No {} devices found in CrowdStrike", parsedDeviceType.name)
            return StreamingSummary(totalVulnerabilities = 0, hostCounts = emptyMap())
        }

        log.info("Found {} {} devices, summarizing in batches of {}", serverDeviceIds.size, parsedDeviceType.name, deviceBatchSize)

        val hostCounts = mutableMapOf<String, Int>()
        val overdueHosts = mutableSetOf<String>()
        var totalVulns = 0
        val deviceChunks = serverDeviceIds.chunked(deviceBatchSize)

        deviceChunks.forEachIndexed { index, chunk ->
            log.info("Summary batch {}/{}: querying vulnerabilities for {} devices",
                index + 1, deviceChunks.size, chunk.size)

            val batchVulns = queryVulnerabilitiesByDeviceIds(
                deviceIds = chunk,
                severity = severity,
                minDaysOpen = minDaysOpen,
                config = config,
                limit = limit
            )

            batchVulns.groupBy { it.hostname }.forEach { (host, vulns) ->
                hostCounts.merge(host, vulns.size) { a, b -> a + b }
                if (vulns.any { parseDaysOpen(it.daysOpen) > overdueThreshold }) {
                    overdueHosts.add(host)
                }
            }
            totalVulns += batchVulns.size
            // batchVulns eligible for GC after this iteration
        }

        log.info("Streaming summary completed: {} total vulnerabilities across {} hosts, {} with overdue vulns",
            totalVulns, hostCounts.size, overdueHosts.size)

        return StreamingSummary(
            totalVulnerabilities = totalVulns,
            hostCounts = hostCounts,
            hostsWithOverdueVulns = overdueHosts.size
        )
    }

    /**
     * Parse daysOpen string to integer value (e.g., "526 days" -> 526)
     */
    private fun parseDaysOpen(daysOpen: String?): Int {
        if (daysOpen.isNullOrBlank()) return 0
        return daysOpen.split(" ").firstOrNull()?.toIntOrNull() ?: 0
    }

    /**
     * Get all server device IDs from CrowdStrike
     *
     * @param deviceType Device type filter (e.g., "SERVER")
     * @param token OAuth2 access token
     * @param limit Maximum number of devices to retrieve per page
     * @return List of device IDs
     */
    @Retryable(
        includes = [RateLimitException::class],
        attempts = "5",
        delay = "1s",
        multiplier = "2.0",
        maxDelay = "60s"
    )
    open fun getAllServerDeviceIds(
        deviceType: String,
        token: AuthToken,
        limit: Int = 5000
    ): List<String> {
        log.info("Querying all devices of type: {}", deviceType)

        val allDeviceIds = mutableListOf<String>()
        var offset = 0
        var hasMore = true

        while (hasMore) {
            try {
                // Query all devices - CrowdStrike doesn't provide a direct device_type filter
                // We'll rely on the vulnerability filtering later to get server-specific vulnerabilities
                // Note: In production, you might want to use platform_name filters like:
                // "platform_name:'Windows'+product_type_desc:'Server'" for Windows Servers
                // For now, we query all devices and let vulnerability filtering do the work

                val uri = UriBuilder.of("/devices/queries/devices/v1")
                    .queryParam("limit", limit.coerceAtMost(5000))
                    .queryParam("offset", offset)
                    .build()

                val request = HttpRequest.GET<Any>(uri.toString())
                    .header("Authorization", "Bearer ${token.accessToken}")
                    .header("Accept", "application/json")

                log.debug("Querying devices: limit={}, offset={}", limit, offset)

                val response = httpClient.toBlocking().exchange(request, Map::class.java)

                when (response.status.code) {
                    200 -> {
                        @Suppress("UNCHECKED_CAST")
                        val responseBody = response.body() as? Map<String, Any>
                            ?: throw CrowdStrikeException("Empty response from CrowdStrike Hosts API")

                        val resources = responseBody["resources"] as? List<*> ?: emptyList<Any>()
                        val deviceIds = resources.mapNotNull { it?.toString() }

                        allDeviceIds.addAll(deviceIds)

                        log.info("Retrieved {} device IDs (total: {})", deviceIds.size, allDeviceIds.size)

                        // Check if there are more pages
                        val meta = responseBody["meta"] as? Map<*, *>
                        val pagination = meta?.get("pagination") as? Map<*, *>
                        val total = (pagination?.get("total") as? Number)?.toInt() ?: deviceIds.size

                        hasMore = allDeviceIds.size < total && deviceIds.isNotEmpty()
                        offset += deviceIds.size

                        if (hasMore) {
                            log.debug("More devices available, continuing pagination (offset: {})", offset)
                        }
                    }
                    404 -> {
                        log.info("No devices found")
                        hasMore = false
                    }
                    429 -> {
                        val retryAfter = response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                        throw RateLimitException("Rate limit exceeded querying devices", retryAfter)
                    }
                    in 500..599 -> throw CrowdStrikeException("CrowdStrike server error: ${response.status}")
                    else -> throw CrowdStrikeException("Unexpected CrowdStrike response: ${response.status}")
                }
            } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
                when (e.status.code) {
                    404 -> {
                        log.info("No devices found")
                        hasMore = false
                    }
                    429 -> {
                        val retryAfter = e.response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                        throw RateLimitException("Rate limit exceeded", retryAfter, e)
                    }
                    in 500..599 -> throw CrowdStrikeException("Server error: ${e.status}", e)
                    else -> throw CrowdStrikeException("API error: ${e.message}", e)
                }
            } catch (e: RateLimitException) {
                throw e
            } catch (e: Exception) {
                log.error("Unexpected error querying device IDs", e)
                throw CrowdStrikeException("Failed to query device IDs: ${e.message}", e)
            }
        }

        return allDeviceIds
    }

    /**
     * Get device IDs from CrowdStrike using product_type_desc filter
     *
     * Supports exact and composite [DeviceType] scopes.
     *
     * @param token OAuth2 access token
     * @param deviceType Device scope to query
     * @param limit Maximum number of devices to retrieve per page
     * @return List of device IDs matching the specified type
     */
    @Retryable(
        includes = [RateLimitException::class],
        attempts = "5",
        delay = "1s",
        multiplier = "2.0",
        maxDelay = "60s"
    )
    open fun getDeviceIdsFiltered(
        token: AuthToken,
        deviceType: DeviceType = DeviceType.SERVER,
        limit: Int = 5000,
        lastSeenDays: Int = 0
    ): List<String> {
        val atomicTypes = deviceType.atomicTypes()
        if (atomicTypes.size > 1) {
            log.info(">>> Stage 1: Expanding {} into exact Falcon types: {}",
                deviceType.name, atomicTypes.joinToString { it.name })
            val combined = atomicTypes
                .flatMap { atomicType -> getDeviceIdsFiltered(token, atomicType, limit, lastSeenDays) }
                .distinct()
            log.info(">>> Stage 1 complete: {} unique {} devices found", combined.size, deviceType.name)
            return combined
        }

        log.info(">>> Stage 1: Querying {} devices with product_type_desc filter{}", deviceType.name,
            if (lastSeenDays > 0) " + last_seen:>'now-${lastSeenDays}d'" else " (no recency filter)")

        val allDeviceIds = mutableListOf<String>()
        var offset = 0
        var hasMore = true

        while (hasMore) {
            try {
                // Filter by device type, optionally restricting to recently seen devices
                // - product_type_desc is an exact Falcon category such as Server,
                //   Domain Controller, or Workstation
                // - last_seen:>'now-Nd' = Only devices seen in the last N days (when lastSeenDays > 0)
                val deviceTypeFilter = requireNotNull(deviceType.toFqlFilter()) {
                    "Composite device scopes must be expanded before querying a single device type"
                }
                val filter = if (lastSeenDays > 0) {
                    "$deviceTypeFilter+last_seen:>'now-${lastSeenDays}d'"
                } else {
                    deviceTypeFilter
                }

                val uri = UriBuilder.of("/devices/queries/devices/v1")
                    .queryParam("filter", filter)
                    .queryParam("limit", limit.coerceAtMost(5000))
                    .queryParam("offset", offset)
                    .build()

                val request = HttpRequest.GET<Any>(uri.toString())
                    .header("Authorization", "Bearer ${token.accessToken}")
                    .header("Accept", "application/json")

                log.info(">>> Stage 1 (page {}): FQL filter: {}", (offset / limit) + 1, filter)
                log.debug(">>> Stage 1 (page {}): limit={}, offset={}", (offset / limit) + 1, limit, offset)

                val response = httpClient.toBlocking().exchange(request, Map::class.java)

                when (response.status.code) {
                    200 -> {
                        @Suppress("UNCHECKED_CAST")
                        val responseBody = response.body() as? Map<String, Any>
                            ?: throw CrowdStrikeException("Empty response from CrowdStrike Hosts API")

                        if (!(responseBody["errors"] as? List<*>).isNullOrEmpty()) throw CrowdStrikeException("Incomplete Falcon device enumeration")
                        val resources = responseBody["resources"] as? List<*>
                            ?: throw CrowdStrikeException("Missing Falcon device IDs")
                        val deviceIds = resources.mapNotNull { it?.toString() }

                        if (deviceIds.isEmpty() && offset > 0) throw CrowdStrikeException("Incomplete Falcon device enumeration")
                        if (deviceIds.any { it in allDeviceIds } || deviceIds.distinct().size != deviceIds.size) {
                            throw CrowdStrikeException("Repeated Falcon device enumeration page")
                        }
                        allDeviceIds.addAll(deviceIds)

                        // Check if there are more pages
                        val meta = responseBody["meta"] as? Map<*, *>
                        val pagination = meta?.get("pagination") as? Map<*, *>
                        val total = (pagination?.get("total") as? Number)?.toInt()
                        if (total != null && (allDeviceIds.size > total || (allDeviceIds.size < total && deviceIds.isEmpty()))) {
                            throw CrowdStrikeException("Incomplete Falcon device enumeration")
                        }

                        log.info(">>> Stage 1 (page {}): Retrieved {} device IDs (total so far: {}, total available: {})",
                            (offset / limit) + 1, deviceIds.size, allDeviceIds.size, total)

                        hasMore = if (total != null) allDeviceIds.size < total else deviceIds.size >= limit.coerceAtMost(5000)
                        offset += deviceIds.size
                        if (hasMore && offset >= 1000000) throw CrowdStrikeException("Falcon device enumeration exceeded safety limit")

                        if (hasMore) {
                            log.info(">>> Stage 1: More devices available, continuing pagination (offset: {})", offset)
                        }
                    }
                    404 -> {
                        if (offset > 0) throw CrowdStrikeException("Incomplete Falcon device enumeration")
                        log.info("No {} devices found", deviceType.name)
                        hasMore = false
                    }
                    429 -> {
                        val retryAfter = response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                        throw RateLimitException("Rate limit exceeded querying ${deviceType.name} devices", retryAfter)
                    }
                    in 500..599 -> throw CrowdStrikeException("CrowdStrike server error: ${response.status}")
                    else -> throw CrowdStrikeException("Unexpected CrowdStrike response: ${response.status}")
                }
            } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
                when (e.status.code) {
                    404 -> {
                        if (offset > 0) throw CrowdStrikeException("Incomplete Falcon device enumeration")
                        log.info("No {} devices found", deviceType.name)
                        hasMore = false
                    }
                    429 -> {
                        val retryAfter = e.response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                        throw RateLimitException("Rate limit exceeded", retryAfter, e)
                    }
                    in 500..599 -> throw CrowdStrikeException("Server error: ${e.status}", e)
                    else -> throw CrowdStrikeException("API error: ${e.message}", e)
                }
            } catch (e: RateLimitException) {
                throw e
            } catch (e: Exception) {
                log.error("Unexpected error querying {} device IDs", deviceType.name, e)
                throw CrowdStrikeException("Failed to query ${deviceType.name} device IDs: ${e.message}", e)
            }
        }

        val uniqueDeviceIds = allDeviceIds.distinct()
        if (uniqueDeviceIds.size < allDeviceIds.size) {
            log.info("Deduplicated device IDs: {} -> {} unique (removed {} duplicates from pagination overlap)",
                allDeviceIds.size, uniqueDeviceIds.size, allDeviceIds.size - uniqueDeviceIds.size)
        }
        log.info(">>> Stage 1 complete: {} {} devices found (lastSeenDays={})", uniqueDeviceIds.size, deviceType.name, lastSeenDays)
        return uniqueDeviceIds
    }

    /**
     * Query vulnerabilities for specific device IDs with filters
     *
     * Optimization: Query vulnerabilities only for specific device IDs (not all devices)
     *
     * @param deviceIds List of device IDs to query
     * @param severity Severity filter (e.g., "HIGH,CRITICAL")
     * @param minDaysOpen Minimum days open filter
     * @param config CrowdStrike Falcon configuration
     * @param limit Page size for pagination
     * @return List of filtered vulnerabilities
     */
    @Retryable(
        includes = [RateLimitException::class],
        attempts = "3",
        delay = "2s",
        multiplier = "2.0",
        maxDelay = "30s"
    )
    open fun queryVulnerabilitiesByDeviceIds(
        deviceIds: List<String>,
        severity: String,
        minDaysOpen: Int,
        config: FalconConfigDto,
        limit: Int = 1000
    ): List<CrowdStrikeVulnerabilityDto> =
        queryVulnerabilitiesByDeviceIdsDetailed(deviceIds, severity, minDaysOpen, config, limit).vulnerabilities

    /**
     * Result of [queryVulnerabilitiesByDeviceIdsDetailed]: the collected vulnerabilities
     * plus the device ids whose batch FAILED or was truncated. The fault-tolerant batch
     * collection continues past failed batches by design — but a caller that later
     * reconciles "hosts with no refreshed rows" must know which hosts were simply never
     * fetched, or it will delete their entire population as stale.
     */
    data class DeviceVulnerabilityQueryResult(
        val vulnerabilities: List<CrowdStrikeVulnerabilityDto>,
        val failedDeviceIds: Set<String>,
        val devices: Set<QueriedHost> = emptySet()
    )

    open fun queryVulnerabilitiesByDeviceIdsDetailed(
        deviceIds: List<String>,
        severity: String,
        minDaysOpen: Int,
        config: FalconConfigDto,
        limit: Int = 1000
    ): DeviceVulnerabilityQueryResult {
        val metadata = selectImportDevices(deviceIds, resolveImportMetadata(deviceIds, config))
        return queryVulnerabilitiesWithMetadata(metadata.keys.toList(), severity, minDaysOpen, config, limit, metadata)
    }

    private fun queryVulnerabilitiesWithMetadata(
        deviceIds: List<String>,
        severity: String,
        minDaysOpen: Int,
        config: FalconConfigDto,
        limit: Int,
        metadataByDeviceId: Map<String, DeviceMetadata>
    ): DeviceVulnerabilityQueryResult {
        if (deviceIds.isEmpty()) {
            log.info("No device IDs provided, returning empty list")
            return DeviceVulnerabilityQueryResult(emptyList(), emptySet())
        }

        val batchSize = configuredBatchSize.coerceIn(5, 200)
        val batches = deviceIds.chunked(batchSize)
        val parallelism = configuredMaxParallelBatches
            .coerceAtLeast(1)
            .coerceAtMost(12)
            .coerceAtMost(batches.size)

        log.info(">>> Stage 2: Querying vulnerabilities (severity={}, minDaysOpen={}, batchSize={}, parallelism={})",
            severity, minDaysOpen, batchSize, parallelism)
        log.info(">>> Stage 2: Split {} device IDs into {} batches", deviceIds.size, batches.size)

        val allVulnerabilities = mutableListOf<CrowdStrikeVulnerabilityDto>()
        val failedDeviceIds = deviceIds.filter { id ->
            val metadata = metadataByDeviceId[id]
            metadata == null || (metadata.hostname.isNullOrBlank() && metadata.cloudInstanceId.isNullOrBlank())
        }.toMutableSet()

        if (batches.size == 1) {
            val outcome = queryBatchWithSplitting(
                batchIndex = 0,
                totalBatches = 1,
                deviceIds = batches.first(),
                severity = severity,
                minDaysOpen = minDaysOpen,
                limit = limit,
                config = config,
                metadataByDeviceId = metadataByDeviceId
            )
            allVulnerabilities.addAll(outcome.vulnerabilities)
            if (outcome.truncated) failedDeviceIds.addAll(outcome.failedDeviceIds)
        } else {
            val executor = createBatchExecutor(parallelism)
            val futures = batches.mapIndexed { index, batch ->
                executor.submit(Callable {
                    queryBatchWithSplitting(
                        batchIndex = index,
                        totalBatches = batches.size,
                        deviceIds = batch,
                        severity = severity,
                        minDaysOpen = minDaysOpen,
                        limit = limit,
                        config = config,
                        metadataByDeviceId = metadataByDeviceId
                    )
                })
            }

            // Collect results with fault tolerance - continue even if some batches fail.
            // Every failed/cancelled/truncated batch's device ids are recorded so callers
            // can exclude those hosts from any staleness-based reconciliation.
            val failedBatches = mutableListOf<Int>()
            val errors = mutableListOf<String>()

            try {
                futures.forEachIndexed { index, future ->
                    try {
                        val outcome = future.get()
                        allVulnerabilities.addAll(outcome.vulnerabilities)
                        if (outcome.truncated) {
                            failedBatches.add(index + 1)
                            failedDeviceIds.addAll(outcome.failedDeviceIds)
                        }
                    } catch (e: ExecutionException) {
                        val cause = e.cause
                        val errorMsg = "Batch ${index + 1}/${batches.size} failed: ${cause?.message ?: e.message}"
                        log.warn(">>> $errorMsg")
                        failedBatches.add(index + 1)
                        errors.add(errorMsg)
                        failedDeviceIds.addAll(batches[index])
                        // Continue with other batches instead of failing entirely
                    } catch (e: java.util.concurrent.CancellationException) {
                        log.warn(">>> Batch ${index + 1}/${batches.size} was cancelled")
                        failedBatches.add(index + 1)
                        failedDeviceIds.addAll(batches[index])
                    }
                }
            } catch (e: InterruptedException) {
                log.warn(">>> Batch processing interrupted, cancelling remaining futures")
                futures.forEach { it.cancel(true) }
                Thread.currentThread().interrupt()
                // Don't throw - return partial results. Interrupted mid-collection means we
                // cannot tell which batches completed: conservatively mark every device
                // failed so a later reconcile sweeps none of them.
                failedDeviceIds.addAll(batches.flatten())
            } finally {
                executor.shutdown()
            }

            // Log summary of failures
            if (failedBatches.isNotEmpty()) {
                log.warn(">>> {} of {} batches failed or were truncated: {}", failedBatches.size, batches.size, failedBatches.joinToString(", "))
                log.warn(">>> Continuing with {} vulnerabilities from successful batches", allVulnerabilities.size)
            }
        }

        val completeRows = allVulnerabilities.filterNot { it.crowdStrikeAid in failedDeviceIds }
        log.info(">>> Stage 2 complete: {} complete vulnerabilities across {} batches ({} excluded device(s))",
            completeRows.size, batches.size, failedDeviceIds.size)
        return DeviceVulnerabilityQueryResult(completeRows, failedDeviceIds,
            deviceIds.mapNotNull { id -> metadataByDeviceId[id]?.toQueriedHost(id) }.toSet())
    }

    /**
     * One Stage-2 batch's collected vulnerabilities plus whether its pagination was cut
     * short (page cap, or a broken/repeating pagination token). A truncated batch's
     * devices have incomplete data and must be treated like a failed batch by callers
     * that reconcile on staleness.
     */
    private data class BatchQueryOutcome(
        val vulnerabilities: List<CrowdStrikeVulnerabilityDto>,
        val truncated: Boolean,
        val pageCapReached: Boolean = false,
        val failedDeviceIds: Set<String> = emptySet()
    )

    private fun queryBatchWithSplitting(
        batchIndex: Int, totalBatches: Int, deviceIds: List<String>, severity: String,
        minDaysOpen: Int, limit: Int, config: FalconConfigDto,
        metadataByDeviceId: Map<String, DeviceMetadata>
    ): BatchQueryOutcome {
        val result = queryBatchVulnerabilities(batchIndex, totalBatches, deviceIds, severity,
            minDaysOpen, limit, config, metadataByDeviceId)
        if (!result.pageCapReached || deviceIds.size <= 1) return result
        // Retry only the bounded page-cap case. Cursor anomalies remain failures, not retries.
        val splitSize = (deviceIds.size + 1) / 2
        log.warn("Splitting oversized Spotlight shard: devices={}, childSize={}", deviceIds.size, splitSize)
        val children = deviceIds.chunked(splitSize).map { ids ->
            queryBatchWithSplitting(batchIndex, totalBatches, ids, severity, minDaysOpen,
                limit, config, metadataByDeviceId)
        }
        return BatchQueryOutcome(children.flatMap { it.vulnerabilities },
            children.any { it.truncated }, failedDeviceIds = children.flatMap { it.failedDeviceIds }.toSet())
    }

    private fun queryBatchVulnerabilities(
        batchIndex: Int,
        totalBatches: Int,
        deviceIds: List<String>,
        severity: String,
        minDaysOpen: Int,
        limit: Int,
        config: FalconConfigDto,
        metadataByDeviceId: Map<String, DeviceMetadata>
    ): BatchQueryOutcome {
        var token = getAuthToken(config)
        // Attribution visibility: downstream, vulnerabilities are grouped by the
        // metadata-derived hostname, so several aids sharing a hostname collapse into
        // one host bucket (~2.4 aids/hostname observed on 2026-08-25). Log the
        // collapse so an inflated per-host count can be traced to its aids.
        metadataByDeviceId.filterKeys { it in deviceIds }.entries
            .groupBy({ it.value.hostname }, { it.key })
            .filter { (hostname, aids) -> hostname != null && aids.size > 1 }
            .forEach { (hostname, aids) ->
                log.info(">>> Batch {}/{}: hostname '{}' maps to {} aids: {}",
                    batchIndex + 1, totalBatches, hostname, aids.size, aids)
            }
        val severityFilter = buildSeverityFilter(severity)
        val deviceIdFilter = buildDeviceIdFilter(deviceIds)
        val fqlFilter = "$deviceIdFilter+status:'open'+$severityFilter"
        val batchVulnerabilities = mutableListOf<CrowdStrikeVulnerabilityDto>()
        val effectiveLimit = limit.coerceAtMost(5000)
        val maxPagesPerBatch = 50
        var afterToken: String? = null
        var hasMore = true
        var pageCount = 0
        var truncated = false
        // Every `after` cursor ever returned for this batch. Falcon has been observed
        // ping-ponging between two cursors (800/255-row pages alternating for 20+ pages,
        // 2026-08-25 import), which the old consecutive-repeat check could not see —
        // the same rows were re-fetched until the page cap and inflated one host to
        // ~27x its real row count.
        val seenAfterTokens = mutableSetOf<String>()
        // Raw (pre-minDaysOpen-filter) rows fetched, checked against Falcon's own
        // meta.pagination.total so the loop can neither overshoot nor end silently short.
        var rawFetched = 0
        var expectedTotal: Int? = null

        log.debug(">>> Batch {}/{} starting with {} device IDs", batchIndex + 1, totalBatches, deviceIds.size)

        // Retry tracking for transient network errors (Feature 053)
        val maxRetries = 3
        val retryCount = mutableMapOf<Int, Int>()  // page -> retry count

        // Helper to handle retry logic for transient errors
        fun retryTransientError(batchIdx: Int, totalBatch: Int, page: Int, errorType: String, e: Exception): Boolean {
            val currentRetries = retryCount.getOrDefault(page, 0)
            if (currentRetries < maxRetries) {
                retryCount[page] = currentRetries + 1
                val backoffMs = (currentRetries + 1) * 2000L  // 2s, 4s, 6s
                log.warn(">>> Batch {}/{} page {}: {} - {}. Retry {}/{} after {}ms",
                    batchIdx + 1, totalBatch, page, errorType, e.message, currentRetries + 1, maxRetries, backoffMs)
                try {
                    Thread.sleep(backoffMs)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                return true
            }
            log.error(">>> Batch {}/{} page {}: {} - {}. Max retries ({}) exceeded",
                batchIdx + 1, totalBatch, page, errorType, e.message, maxRetries)
            return false
        }

        while (hasMore && pageCount < maxPagesPerBatch) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Batch ${batchIndex + 1} interrupted")
            }

            if (token.isExpiringSoon(bufferSeconds = 180)) {
                log.debug(">>> Batch {}/{}: Token expiring soon, refreshing", batchIndex + 1, totalBatches)
                token = getAuthToken(config)
            }

            val uri = UriBuilder.of("/spotlight/combined/vulnerabilities/v1")
                .queryParam("filter", fqlFilter)
                .queryParam("limit", effectiveLimit)
                // host_info carries local_ip and the cloud/domain metadata; without the facet
                // Spotlight omits the object entirely (see queryAllVulnerabilitiesBulk).
                .queryParam("facet", "cve", "host_info")
                .apply {
                    if (afterToken != null) {
                        queryParam("after", afterToken)
                    }
                }
                .build()

            val request = HttpRequest.GET<Any>(uri.toString())
                .header("Authorization", "Bearer ${token.accessToken}")
                .header("Accept", "application/json")

            try {
                val response = httpClient.toBlocking().exchange(request, Map::class.java)
                pageCount++

                when (response.status.code) {
                    200 -> {
                        @Suppress("UNCHECKED_CAST")
                        val responseBody = response.body() as? Map<String, Any>
                            ?: throw CrowdStrikeException("Empty response from Spotlight API")

                        if (!(responseBody["errors"] as? List<*>).isNullOrEmpty()) throw CrowdStrikeException("Incomplete Falcon findings page")
                        val resources = responseBody["resources"] as? List<*> ?: throw CrowdStrikeException("Missing Falcon findings")
                        val vulns = mapResponseToDtos(resources, metadataByDeviceId)
                        if (vulns.size != resources.size || vulns.any { it.crowdStrikeAid !in deviceIds }) {
                            throw CrowdStrikeException("Falcon findings do not match the selected devices")
                        }

                        val filtered = vulns.filter { vuln ->
                            val daysOpenValue = vuln.daysOpen?.split(" ")?.firstOrNull()?.toIntOrNull() ?: 0
                            daysOpenValue >= minDaysOpen
                        }

                        val meta = responseBody["meta"] as? Map<*, *>
                        val pagination = meta?.get("pagination") as? Map<*, *>
                        afterToken = pagination?.get("after")?.toString()
                        if (expectedTotal == null) {
                            expectedTotal = (pagination?.get("total") as? Number)?.toInt()
                        }
                        rawFetched += vulns.size

                        log.info(">>> Batch {}/{} page {}: Retrieved {} vulns, {} after filter (batch total: {})",
                            batchIndex + 1, totalBatches, pageCount, vulns.size, filtered.size,
                            batchVulnerabilities.size + filtered.size)
                        log.debug(">>> Batch {}/{} page {}: Pagination - afterToken: {}, vulns.isNotEmpty: {}",
                            batchIndex + 1, totalBatches, pageCount,
                            if (afterToken != null) "present" else "null",
                            vulns.isNotEmpty())

                        batchVulnerabilities.addAll(filtered)

                        val liveToken = afterToken
                        if (liveToken != null && !seenAfterTokens.add(liveToken)) {
                            // A cursor we have already followed: Falcon is looping (not
                            // necessarily on consecutive pages). Fail the batch rather than
                            // keep partial rows — the backend import is a per-host
                            // delete-then-insert replace, so a partial payload would
                            // silently delete real rows.
                            log.warn(">>> Batch {}/{} page {}: pagination cursor loop detected (after-token seen before) - failing batch",
                                batchIndex + 1, totalBatches, pageCount)
                            hasMore = false
                            truncated = true
                        } else {
                            // Continue only on a full page: Spotlight returns a live `after`
                            // token even on the final page, so token presence alone never
                            // terminates (same rule as querySpotlightApi and
                            // queryAllVulnerabilitiesBulk). Falcon's own total is a further
                            // upper bound when it reports one.
                            val total = expectedTotal
                            hasMore = liveToken != null && vulns.isNotEmpty() &&
                                vulns.size >= effectiveLimit &&
                                (total == null || rawFetched < total)

                            if (!hasMore && liveToken != null && total != null &&
                                rawFetched < total && vulns.size < effectiveLimit
                            ) {
                                // Short page although Falcon still reports more rows: the
                                // result set is incomplete, so the batch must not be treated
                                // as a full refresh of its hosts.
                                log.warn(">>> Batch {}/{} page {}: short page after {} of {} reported rows - marking batch truncated",
                                    batchIndex + 1, totalBatches, pageCount, rawFetched, total)
                                truncated = true
                            }
                        }
                    }
                    404 -> hasMore = false
                    429 -> {
                        val retryAfter = response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                        throw RateLimitException("Rate limit exceeded on batch ${batchIndex + 1}", retryAfter)
                    }
                    in 500..599 -> throw CrowdStrikeException("Spotlight API server error: ${response.status}")
                    else -> throw CrowdStrikeException("Unexpected Spotlight API response: ${response.status}")
                }
            } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
                when (e.status.code) {
                    401 -> {
                        log.warn(">>> Batch {}/{}: Unauthorized - refreshing token (expires at: {})",
                            batchIndex + 1, totalBatches, token.expiresAt)
                        authService.clearCache()
                        token = getAuthToken(config)
                        continue
                    }
                    404 -> hasMore = false
                    429 -> {
                        val retryAfter = e.response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                        log.warn(">>> Batch {}/{}: Rate limit, retrying after {}s",
                            batchIndex + 1, totalBatches, retryAfter)
                        throw RateLimitException("Rate limit exceeded", retryAfter, e)
                    }
                    in 500..599 -> {
                        // Server errors are often transient - retry with backoff
                        if (retryTransientError(batchIndex, totalBatches, pageCount, "Server error ${e.status.code}", e)) {
                            continue
                        }
                        throw CrowdStrikeException("Server error: ${e.status}", e)
                    }
                    else -> {
                        log.error(">>> Batch {}/{}: API error {}", batchIndex + 1, totalBatches, e.status)
                        throw CrowdStrikeException("API error: ${e.message}", e)
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Retry transient network errors with backoff
                if (retryTransientError(batchIndex, totalBatches, pageCount, "Timeout", e)) {
                    continue
                }
                throw CrowdStrikeException("Timeout waiting for CrowdStrike API response on batch ${batchIndex + 1}", e)
            } catch (e: java.io.IOException) {
                if (retryTransientError(batchIndex, totalBatches, pageCount, "Network I/O error", e)) {
                    continue
                }
                throw CrowdStrikeException("Network error on batch ${batchIndex + 1}: ${e.message}", e)
            } catch (e: io.netty.channel.ChannelException) {
                if (retryTransientError(batchIndex, totalBatches, pageCount, "Channel error", e)) {
                    continue
                }
                throw CrowdStrikeException("Channel error during batch ${batchIndex + 1}: ${e.message}", e)
            } catch (e: io.micronaut.http.client.exceptions.HttpClientException) {
                val isTransient = e.message?.contains("Channel closed") == true ||
                    e.message?.contains("aggregating") == true ||
                    e.message?.contains("Connection closed") == true
                if (isTransient && retryTransientError(batchIndex, totalBatches, pageCount, "HTTP client error", e)) {
                    continue
                }
                throw CrowdStrikeException("HTTP client error during batch ${batchIndex + 1}: ${e.message}", e)
            } catch (e: RateLimitException) {
                throw e
            } catch (e: CrowdStrikeException) {
                throw e
            } catch (e: Exception) {
                log.error(">>> Batch {}/{}: Unexpected error (no retry): {} - {}",
                    batchIndex + 1, totalBatches, e.javaClass.simpleName, e.message)
                throw CrowdStrikeException("Failed to query batch ${batchIndex + 1}: ${e.message}", e)
            }
        }

        if (hasMore && pageCount >= maxPagesPerBatch) {
            if (deviceIds.size > 1) {
                log.warn("Spotlight shard reached page cap; retrying smaller shards: devices={}, pages={}",
                    deviceIds.size, maxPagesPerBatch)
            } else {
                log.error("Spotlight device reached page cap with data pending: pages={}", maxPagesPerBatch)
            }
            truncated = true
        }

        log.debug(">>> Batch {}/{} complete: {} vulnerabilities collected (truncated={})",
            batchIndex + 1, totalBatches, batchVulnerabilities.size, truncated)

        return BatchQueryOutcome(if (truncated) emptyList() else batchVulnerabilities, truncated,
            pageCapReached = hasMore && pageCount >= maxPagesPerBatch,
            failedDeviceIds = if (truncated) deviceIds.toSet() else emptySet())
    }

    private fun createBatchExecutor(parallelism: Int): ExecutorService {
        val safeParallelism = parallelism.coerceAtLeast(1)
        val threadCounter = AtomicInteger(1)
        return Executors.newFixedThreadPool(safeParallelism) { runnable ->
            Thread(runnable, "crowdstrike-batch-${threadCounter.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
    }

    private fun buildSeverityFilter(severity: String): String {
        val values = severity.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.uppercase() }
            .ifEmpty { listOf("HIGH") }

        return if (values.size == 1) {
            "cve.severity:'${values.first()}'"
        } else {
            "cve.severity:[${values.joinToString(",") { "'$it'" }}]"
        }
    }

    private fun buildDeviceIdFilter(deviceIds: List<String>): String {
        return if (deviceIds.size == 1) {
            "aid:'${deviceIds.first()}'"
        } else {
            "aid:[${deviceIds.joinToString(",") { "'$it'" }}]"
        }
    }

    /**
     * Get the authorization token
     *
     * @param config CrowdStrike configuration
     * @return AuthToken for API requests
     */
    override fun getAuthToken(config: FalconConfigDto): AuthToken {
        return authService.authenticate(config)
    }

    /**
     * Get device ID by hostname using multi-strategy approach
     *
     * Tries multiple filter strategies before falling back to error.
     *
     * @param hostname System hostname
     * @param token OAuth2 access token
     * @return Device ID or null if not found
     */
    @Retryable(
        includes = [RateLimitException::class],
        attempts = "5",
        delay = "1s",
        multiplier = "2.0",
        maxDelay = "60s"
    )
    open fun getDeviceIdByHostname(hostname: String, token: AuthToken): String? {
        val ids = getDeviceIdsByHostname(hostname, token)
        if (ids.isEmpty()) return null
        val metadata = selectImportDevices(ids, resolveDeviceMetadata(ids, token))
        val matching = metadata.filterValues { it.hostname.equals(hostname, ignoreCase = true) ||
            (!hostname.contains('.') && it.hostname?.substringBefore(".").equals(hostname, ignoreCase = true)) }
        if (matching.size != 1) throw CrowdStrikeException("Ambiguous Falcon hostname")
        return matching.keys.single()
    }

    /** Discover all exact matches before considering a short-name/FQDN fallback. */
    open fun getDeviceIdsByHostname(hostname: String, token: AuthToken): List<String> {
        require(hostname.matches(Regex("[A-Za-z0-9_.-]{1,255}"))) { "Invalid hostname" }
        val strategies = listOf(hostname, hostname.lowercase(), hostname.uppercase()).distinct()
            .map { "hostname:'$it'" } + listOf("hostname:'$hostname*'", "hostname:'*$hostname*'")
        for (filter in strategies) {
            val ids = queryAllMatchingDeviceIds(filter, token)
            if (ids.isNotEmpty()) return ids
        }
        return emptyList()
    }

    private fun queryAllMatchingDeviceIds(filter: String, token: AuthToken, pageLimit: Int = 100): List<String> {
        val ids = linkedSetOf<String>()
        var offset = 0
        while (true) {
            val uri = UriBuilder.of("/devices/queries/devices/v1")
                .queryParam("filter", filter).queryParam("limit", pageLimit).queryParam("offset", offset).build()
            val request = HttpRequest.GET<Any>(uri.toString())
                .header("Authorization", "Bearer ${token.accessToken}").header("Accept", "application/json")
            val response = try {
                httpClient.toBlocking().exchange(request, Map::class.java)
            } catch (e: HttpClientResponseException) {
                if (e.status.code == 404 && offset == 0) return emptyList()
                if (e.status.code == 429) throw RateLimitException("Rate limit during device lookup",
                    e.response.headers.get("Retry-After")?.toLongOrNull() ?: 30L, e)
                throw CrowdStrikeException("Falcon device lookup failed", e)
            }
            if (response.status.code != 200) throw CrowdStrikeException("Falcon device lookup failed")
            val body = response.body() as? Map<*, *> ?: throw CrowdStrikeException("Empty Falcon device lookup")
            if (!(body["errors"] as? List<*>).isNullOrEmpty()) throw CrowdStrikeException("Incomplete Falcon device lookup")
            val resources = body["resources"] as? List<*> ?: throw CrowdStrikeException("Missing Falcon device IDs")
            val page = resources.map { it?.toString() ?: throw CrowdStrikeException("Invalid Falcon device ID") }
            val pagination = (body["meta"] as? Map<*, *>)?.get("pagination") as? Map<*, *>
            val total = (pagination?.get("total") as? Number)?.toLong()
            if (page.any { !ids.add(it) }) throw CrowdStrikeException("Repeated Falcon device lookup page")
            offset += page.size
            if (total != null && offset.toLong() == total) return ids.toList()
            if (total != null && (offset > total || page.isEmpty())) throw CrowdStrikeException("Incomplete Falcon device enumeration")
            if (total == null && page.size < pageLimit) return ids.toList()
            if (offset >= 1000000) throw CrowdStrikeException("Falcon device enumeration exceeded safety limit")
        }
    }

    /**
     * Query CrowdStrike Spotlight API for vulnerabilities with pagination and retry logic
     *
     * Enhancement: Added pagination support, timeout retry with exponential backoff
     *
     * @param deviceId Device ID (aid) from Hosts API
     * @param hostname Hostname for this device (to include in results)
     * @param token OAuth2 access token
     * @return List of CrowdStrikeVulnerabilityDto
     * @throws RateLimitException if rate limit exceeded (will retry)
     * @throws CrowdStrikeException for other API errors
     */
    @Retryable(
        includes = [RateLimitException::class],
        attempts = "5",
        delay = "1s",
        multiplier = "2.0",
        maxDelay = "60s"
    )
    open fun querySpotlightApi(deviceId: String, hostname: String, token: AuthToken): List<CrowdStrikeVulnerabilityDto> {
        log.debug("Querying Spotlight API for device ID: {}", deviceId)

        val allVulnerabilities = mutableListOf<CrowdStrikeVulnerabilityDto>()
        var afterToken: String? = null
        var hasMore = true
        var pageCount = 0
        val maxPages = 100  // Safety limit to prevent infinite loops
        // Anti-loop guard: every cursor followed so far (see queryBatchVulnerabilities).
        val seenAfterTokens = mutableSetOf<String>()
        val seenResourceIds = mutableSetOf<String>()
        var expectedTotal: Long? = null
        val logDeviceId = deviceId.replace("\r", "").replace("\n", "")

        // Pagination configuration - start with smaller page size for reliability
        var currentLimit = 500  // Start conservative, can handle most systems without pagination
        val minLimit = 100      // Minimum page size on retry
        val maxRetries = 3      // Max retries per page for timeout errors

        val filter = "aid:'$deviceId'+status:'open'"

        while (hasMore && pageCount < maxPages) {
            pageCount++
            var retryCount = 0
            var pageSuccess = false

            while (!pageSuccess && retryCount <= maxRetries) {
                try {
                    val uri = UriBuilder.of("/spotlight/combined/vulnerabilities/v1")
                        .queryParam("filter", filter)
                        .queryParam("limit", currentLimit)
                        // host_info carries local_ip and the cloud/domain metadata; this path maps
                        // straight off the vulnerability record, so without the facet the asset is
                        // stored with no IP at all (see queryAllVulnerabilitiesBulk).
                        .queryParam("facet", "cve", "host_info")
                        .apply {
                            if (afterToken != null) {
                                queryParam("after", afterToken)
                            }
                        }
                        .build()

                    val request = HttpRequest.GET<Any>(uri.toString())
                        .header("Authorization", "Bearer ${token.accessToken}")
                        .header("Accept", "application/json")

                    log.debug("Spotlight API request: page={}, limit={}, filter={}, afterToken={}",
                        pageCount, currentLimit, filter, if (afterToken != null) "present" else "absent")

                    val response = httpClient.toBlocking().exchange(request, Map::class.java)

                    when (response.status.code) {
                        200 -> {
                            @Suppress("UNCHECKED_CAST")
                            val responseBody = response.body() as? Map<String, Any>
                                ?: throw CrowdStrikeException("Empty response from Spotlight API")

                            val resources = responseBody["resources"] as? List<*>
                                ?: throw CrowdStrikeException("Invalid Spotlight resources")
                            val meta = responseBody["meta"] as? Map<*, *>
                            val pagination = meta?.get("pagination") as? Map<*, *>
                            if (expectedTotal == null) {
                                expectedTotal = (pagination?.get("total") as? Number)?.toLong()
                                if (expectedTotal != null && expectedTotal!! < 0) {
                                    throw CrowdStrikeException("Invalid Spotlight pagination total")
                                }
                            }
                            val newAfterToken = pagination?.get("after")?.toString()?.takeIf { it.isNotBlank() }
                            val resourceIds = resources.map { resource ->
                                (resource as? Map<*, *>)?.get("id")?.toString()?.takeIf { it.isNotBlank() }
                                    ?: throw CrowdStrikeException("Missing Spotlight resource identity")
                            }
                            if (resources.any { resource ->
                                val record = resource as? Map<*, *> ?: return@any true
                                val aid = record["aid"]?.toString() ?: record["device_id"]?.toString()
                                aid != null && aid != deviceId
                            }) throw CrowdStrikeException("Falcon findings belong to a different device")
                            val vulns = mapResponseToDtos(resources, hostname, deviceId)
                            if (vulns.size != resources.size) throw CrowdStrikeException("Incomplete Falcon findings mapping")
                            if (vulns.size != resources.size) {
                                throw CrowdStrikeException("Incomplete Spotlight resource mapping")
                            }
                            allVulnerabilities.addAll(vulns.filterIndexed { index, _ ->
                                seenResourceIds.add(resourceIds[index])
                            })

                            log.info("Spotlight device={} page={}: retrieved={}, unique={}, reportedTotal={}, cursor={}",
                                logDeviceId, pageCount, resources.size, seenResourceIds.size, expectedTotal,
                                if (newAfterToken != null) "present" else "absent")

                            val total = expectedTotal
                            // Falcon can leave a live cursor after the final page. Only proven
                            // completeness overrides the cursor guard; short pages can still continue.
                            val failure = when {
                                total != null && seenResourceIds.size > total -> "rows exceed reported total"
                                total != null && seenResourceIds.size.toLong() == total -> null
                                newAfterToken == null && total != null -> "cursor ended before reported total"
                                newAfterToken != null && resources.isEmpty() -> "empty page before completion"
                                newAfterToken != null && !seenAfterTokens.add(newAfterToken) -> "repeated cursor before completion"
                                else -> null
                            }
                            if (failure != null) {
                                log.warn("Incomplete Spotlight pagination: device={}, page={}, unique={}, reportedTotal={}, reason={}",
                                    logDeviceId, pageCount, seenResourceIds.size, total, failure)
                                throw CrowdStrikeException("Incomplete Spotlight pagination: $failure")
                            }
                            hasMore = newAfterToken != null &&
                                (total == null || seenResourceIds.size.toLong() < total)
                            afterToken = newAfterToken
                            pageSuccess = true
                        }
                        404 -> {
                            if (afterToken != null) throw CrowdStrikeException("Incomplete Spotlight query: 404 after cursor")
                            log.info("Spotlight API returned 404 for device {}. Treating as no vulnerabilities.", deviceId)
                            hasMore = false
                            pageSuccess = true
                        }
                        429 -> {
                            val retryAfter = response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                            throw RateLimitException("Rate limit exceeded on Spotlight API", retryAfter)
                        }
                        in 500..599 -> throw CrowdStrikeException("Spotlight API server error: ${response.status}")
                        else -> throw CrowdStrikeException("Unexpected Spotlight API response: ${response.status}")
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    retryCount++
                    if (retryCount <= maxRetries) {
                        // Reduce page size on timeout and retry
                        val newLimit = (currentLimit / 2).coerceAtLeast(minLimit)
                        val backoffMs = retryCount * 2000L  // 2s, 4s, 6s backoff
                        log.warn("Spotlight API timeout on page {} (limit={}). Retry {}/{} with limit={} after {}ms",
                            pageCount, currentLimit, retryCount, maxRetries, newLimit, backoffMs)
                        currentLimit = newLimit
                        Thread.sleep(backoffMs)
                    } else {
                        log.error("Spotlight API timeout: max retries ({}) exceeded on page {}. Discarding incomplete results ({} vulns)",
                            maxRetries, pageCount, allVulnerabilities.size)
                        throw CrowdStrikeException("Incomplete Spotlight query after timeout retries")
                    }
                } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
                    when (e.status.code) {
                        404 -> {
                            if (afterToken != null) throw CrowdStrikeException("Incomplete Spotlight query: 404 after cursor")
                            log.info("Spotlight API returned 404 for device. Treating as no vulnerabilities.")
                            hasMore = false
                            pageSuccess = true
                        }
                        429 -> {
                            val retryAfter = e.response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                            throw RateLimitException("Rate limit exceeded", retryAfter, e)
                        }
                        in 500..599 -> {
                            retryCount++
                            if (retryCount <= maxRetries) {
                                val backoffMs = retryCount * 2000L
                                log.warn("Spotlight API server error {} on page {}. Retry {}/{} after {}ms",
                                    e.status.code, pageCount, retryCount, maxRetries, backoffMs)
                                Thread.sleep(backoffMs)
                            } else {
                                throw CrowdStrikeException("Server error after $maxRetries retries: ${e.status}", e)
                            }
                        }
                        else -> throw CrowdStrikeException("API error: ${e.message}", e)
                    }
                } catch (e: io.micronaut.http.client.exceptions.HttpClientException) {
                    val isTimeout = e.message?.contains("Read Timeout", ignoreCase = true) == true ||
                        e.message?.contains("timeout", ignoreCase = true) == true ||
                        e.message?.contains("Channel closed", ignoreCase = true) == true

                    if (isTimeout) {
                        retryCount++
                        if (retryCount <= maxRetries) {
                            val newLimit = (currentLimit / 2).coerceAtLeast(minLimit)
                            val backoffMs = retryCount * 2000L
                            log.warn("Spotlight API HTTP client timeout on page {} (limit={}). Retry {}/{} with limit={} after {}ms. Error: {}",
                                pageCount, currentLimit, retryCount, maxRetries, newLimit, backoffMs, e.message)
                            currentLimit = newLimit
                            Thread.sleep(backoffMs)
                        } else {
                            throw CrowdStrikeException("Incomplete Spotlight query after HTTP timeout retries", e)
                        }
                    } else {
                        throw CrowdStrikeException("HTTP client error: ${e.message}", e)
                    }
                } catch (e: RateLimitException) {
                    throw e
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CrowdStrikeException("Query interrupted", e)
                } catch (e: Exception) {
                    log.error("Unexpected error querying Spotlight API on page {}", pageCount, e)
                    throw CrowdStrikeException("Failed to query Spotlight API: ${e.message}", e)
                }
            }
        }

        if (hasMore) {
            throw CrowdStrikeException("Incomplete Spotlight query at page limit")
        }

        log.info("Spotlight API query complete: device={}, hostname={}, pages={}, totalVulnerabilities={}",
            deviceId, hostname, pageCount, allVulnerabilities.size)

        return allVulnerabilities
    }

    /**
     * Map CrowdStrike API response to DTOs
     *
     * @param resources Raw vulnerability resources from API
     * @param hostname Hostname for the device (from query context)
     * @return List of CrowdStrikeVulnerabilityDto
     */
    private fun mapResponseToDtos(
        resources: List<*>,
        hostname: String
    ): List<CrowdStrikeVulnerabilityDto> = mapResponseToDtos(resources, hostname, null)

    private fun mapResponseToDtos(
        resources: List<*>,
        hostname: String,
        crowdStrikeAid: String?
    ): List<CrowdStrikeVulnerabilityDto> {
        return resources.mapNotNull { resource ->
            val vuln = resource as? Map<*, *> ?: return@mapNotNull null
            try {

                val id = vuln["id"]?.toString() ?: "cs-${System.currentTimeMillis()}"
                val hostInfo = vuln["host_info"] as? Map<*, *>
                val deviceInfo = vuln["device"] as? Map<*, *>

                // Use the hostname from query context (we already know it!)
                // The CrowdStrike Spotlight API doesn't reliably return hostname in responses
                // but we have it from the original query

                val ip = vuln["local_ip"]?.toString()
                    ?: hostInfo?.get("local_ip")?.toString()
                    ?: deviceInfo?.get("local_ip")?.toString()

                // Extract Active Directory domain (Feature 043)
                val adDomain = hostInfo?.get("machine_domain")?.toString()

                // Extract cloud metadata from host_info (Feature 082)
                val cloudAccountId = hostInfo?.get("service_provider_account_id")?.toString()
                val cloudInstanceId = hostInfo?.get("instance_id")?.toString()

                // Extract operating system from host_info; os_version is the detailed
                // string (e.g. "Windows Server 2019"), platform is a coarse fallback.
                val osVersion = hostInfo?.get("os_version")?.toString()?.takeIf { it.isNotBlank() }
                    ?: hostInfo?.get("platform")?.toString()?.takeIf { it.isNotBlank() }

                // Extract CVE object for multiple field access
                val cveObject = vuln["cve"] as? Map<*, *>
                val cveId = cveObject?.get("id")?.toString()
                val cvssScore = (vuln["score"] as? Number)?.toDouble()
                    ?: (cveObject?.get("base_score") as? Number)?.toDouble()

                // Get severity from multiple possible locations in CrowdStrike API response
                // Priority: cve.severity > vuln.severity > derived from CVSS score
                val apiSeverity = cveObject?.get("severity")?.toString()
                    ?: vuln["severity"]?.toString()
                    ?: vuln["cve_severity"]?.toString()

                val severity = if (!apiSeverity.isNullOrBlank()) {
                    // Normalize API severity to standard format (CRITICAL -> Critical)
                    normalizeSeverity(apiSeverity)
                } else if (cvssScore != null) {
                    // Fallback: derive from CVSS score
                    val derivedSeverity = mapCvssToSeverity(cvssScore)
                    log.debug("Derived severity '{}' from CVSS score {} for CVE {}", derivedSeverity, cvssScore, cveId)
                    derivedSeverity
                } else {
                    // Last resort: default to "Medium" to satisfy @NotBlank validation
                    log.warn("No severity or CVSS score found for vulnerability {}, defaulting to 'Medium'", cveId ?: id)
                    "Medium"
                }

                val apps = vuln["apps"] as? List<*>
                val affectedProduct = apps?.mapNotNull { app ->
                    (app as? Map<*, *>)?.get("product_name_version")?.toString()
                }?.joinToString(", ")

                val createdTimestamp = vuln["created_timestamp"]?.toString()
                    ?: vuln["created_on"]?.toString()
                // Shared with the ad-hoc lookup path so the two cannot drift (see FalconTimestamps).
                // Deliberately NOT coerced to now(): an unknown detection date stays
                // unknown, so the row reads "unknown age" rather than "zero days old"
                // (which would silently never be overdue). See FalconTimestamps.
                val detectedAt = FalconTimestamps.parse(createdTimestamp)
                if (detectedAt == null && !createdTimestamp.isNullOrBlank()) {
                    log.warn(
                        "Unparseable created_timestamp '{}' — reporting unknown detection date",
                        FalconTimestamps.sanitizeForLog(createdTimestamp)
                    )
                }

                // Extract patch publication date
                val patchPublicationDate = extractPatchPublicationDate(vuln)

                val dto = CrowdStrikeVulnerabilityDto(
                    id = id,
                    hostname = hostname,
                    ip = ip,
                    adDomain = adDomain,  // Feature 043
                    osVersion = osVersion,
                    cveId = cveId,
                    severity = severity,
                    cvssScore = cvssScore,
                    affectedProduct = affectedProduct,
                    daysOpen = calculateDaysOpen(detectedAt),
                    detectedAt = detectedAt,
                    patchPublicationDate = patchPublicationDate,
                    cvePublishedDate = extractCvePublishedDate(vuln),
                    status = vuln["status"]?.toString() ?: "open",
                    hasException = false,
                    exceptionReason = null,
                    cloudAccountId = cloudAccountId,
                    cloudInstanceId = cloudInstanceId,
                    crowdStrikeAid = crowdStrikeAid
                )

                log.trace("Mapped vulnerability: CVE={}, severity={}, cvssScore={}, hostname={}",
                    cveId, severity, cvssScore, hostname)

                dto
            } catch (e: Exception) {
                log.error("Failed to map vulnerability from CrowdStrike response. Error: {}", e.message, e)
                log.error("Problematic vulnerability data: id={}, cveId={}, hostname={}",
                    vuln["id"], (vuln["cve"] as? Map<*, *>)?.get("id"), vuln["hostname"])
                log.error("Raw CVE object: {}", vuln["cve"])
                null
            }
        }
    }

    /**
     * Normalize severity from CrowdStrike API format to standard format
     *
     * CrowdStrike returns: CRITICAL, HIGH, MEDIUM, LOW, INFORMATIONAL
     * We normalize to: Critical, High, Medium, Low, Informational
     *
     * @param apiSeverity Severity string from CrowdStrike API
     * @return Normalized severity string
     */
    private fun normalizeSeverity(apiSeverity: String): String {
        return when (apiSeverity.uppercase()) {
            "CRITICAL" -> "Critical"
            "HIGH" -> "High"
            "MEDIUM" -> "Medium"
            "LOW" -> "Low"
            "INFORMATIONAL" -> "Informational"
            else -> apiSeverity.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Map CVSS score to severity level (fallback when API severity is missing)
     */
    private fun mapCvssToSeverity(score: Double?): String {
        return when {
            score == null -> "Unknown"
            score >= 9.0 -> "Critical"
            score >= 7.0 -> "High"
            score >= 4.0 -> "Medium"
            score >= 0.1 -> "Low"
            else -> "Unknown"
        }
    }

    /**
     * Calculate days open since detection
     */
    /**
     * Days since detection, or null when the detection date is unknown.
     *
     * Returning null rather than "0 days" is the point: a fabricated zero reads as a
     * brand-new finding and never trips an overdue threshold.
     */
    private fun calculateDaysOpen(detectedAt: LocalDateTime?): String? {
        if (detectedAt == null) return null
        val days = java.time.temporal.ChronoUnit.DAYS.between(detectedAt, LocalDateTime.now())
        return if (days == 1L) "1 day" else "$days days"
    }

    /**
     * Extract patch publication date from CrowdStrike API response.
     * Tries multiple possible field locations:
     * - cve.published_date
     * - cve.published
     * - remediation.published_date
     * - patch_published_date
     *
     * @param vuln The vulnerability response object
     * @return Parsed LocalDateTime or null if not found/parseable
     */
    private fun extractPatchPublicationDate(vuln: Map<*, *>): LocalDateTime? =
        FalconTimestamps.patchPublicationDate(vuln)

    private fun extractCvePublishedDate(vuln: Map<*, *>): LocalDateTime? =
        FalconTimestamps.cvePublishedDate(vuln)


    private data class DeviceMetadata(
        val hostname: String?,
        val ip: String?,
        val ipAddresses: Set<String>,
        val adDomain: String?,  // Feature 043: Active Directory domain
        val cloudAccountId: String?,
        val cloudInstanceId: String?,
        val osVersion: String?,  // Operating system reported by the device entity
        val lastSeen: Instant? = null,
        val firstSeen: Instant? = null,
        val selection: CrowdStrikeDeviceSelection? = null
    ) {
        fun toQueriedHost(aid: String) = QueriedHost(hostname, cloudInstanceId, aid,
            cloudAccountId, adDomain, osVersion, ip, lastSeen, selection)
    }

    private fun selectImportDevices(
        ids: List<String>, metadata: Map<String, DeviceMetadata>, lastSeenDays: Int = 0
    ): Map<String, DeviceMetadata> {
        if (ids.any { it !in metadata }) throw CrowdStrikeException("Incomplete Falcon device metadata")
        val selections = try {
            selectLatestCrowdStrikeDevices(ids.distinct().map { id ->
                val md = metadata.getValue(id)
                // Falcon can omit a cloud device's hostname while retaining its instance identity.
                CrowdStrikeDeviceRecord(id, firstNonBlank(md.hostname, md.cloudInstanceId).orEmpty(), md.cloudInstanceId,
                    md.cloudAccountId, md.adDomain, md.firstSeen, md.lastSeen)
            })
        } catch (e: IllegalArgumentException) {
            log.warn("Latest Falcon device selection rejected: {}", e.message)
            throw CrowdStrikeException("Cannot select the latest Falcon device safely", e)
        }
        val cutoff = Instant.now().minusSeconds(lastSeenDays.toLong() * 86400)
        return selections.filter { lastSeenDays <= 0 || it.selected.lastSeen?.isAfter(cutoff) == true }
            .associate { selection ->
                val id = selection.selected.aid
                log.info("Latest Falcon device: aid={}, firstSeen={}, lastSeen={}, ignored={}", id,
                    selection.selected.firstSeen, selection.selected.lastSeen, selection.superseded.size)
                id to metadata.getValue(id).copy(hostname = selection.selected.hostname, selection = selection)
            }
    }

    private fun hasNoOpenFindings(deviceId: String, token: AuthToken): Boolean {
        val uri = UriBuilder.of("/spotlight/combined/vulnerabilities/v1")
            .queryParam("filter", "aid:'$deviceId'+status:'open'")
            .queryParam("limit", 1)
            .build()
        val request = HttpRequest.GET<Any>(uri.toString())
            .header("Authorization", "Bearer ${token.accessToken}")
            .header("Accept", "application/json")
        val response = httpClient.toBlocking().exchange(request, Map::class.java)
        if (response.status.code != 200) return false
        val body = response.body() as? Map<*, *> ?: return false
        val resources = body["resources"] as? List<*> ?: return false
        val pagination = (body["meta"] as? Map<*, *>)?.get("pagination") as? Map<*, *>
        val total = pagination?.get("total") as? Number ?: return false
        return resources.isEmpty() && total.toLong() == 0L && (body["errors"] as? List<*>).isNullOrEmpty()
    }

    private fun resolveImportMetadata(
        deviceIds: List<String>, config: FalconConfigDto
    ): Map<String, DeviceMetadata> {
        val executor = createBatchExecutor(4)
        val metadata = mutableMapOf<String, DeviceMetadata>()
        try {
            deviceIds.chunked(100).chunked(4).forEach { wave ->
                val futures = wave.map { chunk ->
                    executor.submit(Callable { resolveDeviceMetadata(chunk, getAuthToken(config)) })
                }
                futures.forEach { metadata.putAll(it.get()) }
            }
        } finally {
            executor.shutdownNow()
        }
        return metadata
    }

    private fun resolveDeviceMetadata(
        deviceIds: List<String>,
        token: AuthToken
    ): Map<String, DeviceMetadata> {
        if (deviceIds.isEmpty()) {
            return emptyMap()
        }

        val metadataByDeviceId = mutableMapOf<String, DeviceMetadata>()
        val chunkSize = 100

        deviceIds.chunked(chunkSize).forEach { chunk ->
            try {
                var uriBuilder = UriBuilder.of("/devices/entities/devices/v2")
                chunk.forEach { id ->
                    uriBuilder = uriBuilder.queryParam("ids", id)
                }
                val uri = uriBuilder.build()

                val request = HttpRequest.GET<Any>(uri.toString())
                    .header("Authorization", "Bearer ${token.accessToken}")
                    .header("Accept", "application/json")

                val response = httpClient.toBlocking().exchange(request, Map::class.java)

                if (response.status.code != 200) {
                    log.warn("Device metadata request returned status {} for {} device IDs", response.status.code, chunk.size)
                    return@forEach
                }

                @Suppress("UNCHECKED_CAST")
                val responseBody = response.body() as? Map<String, Any> ?: return@forEach
                if (!(responseBody["errors"] as? List<*>).isNullOrEmpty()) throw CrowdStrikeException("Incomplete Falcon device metadata")
                val resources = responseBody["resources"] as? List<*> ?: throw CrowdStrikeException("Missing Falcon device metadata")

                resources.forEach { resource ->
                    val device = resource as? Map<*, *> ?: return@forEach
                    val nestedDevice = device["device"] as? Map<*, *>

                    val deviceId = firstNonBlank(
                        device["device_id"]?.toString(),
                        nestedDevice?.get("device_id")?.toString(),
                        device["id"]?.toString(),
                        device["aid"]?.toString()
                    )

                    if (deviceId.isNullOrBlank() || deviceId !in chunk || deviceId in metadataByDeviceId) {
                        throw CrowdStrikeException("Invalid or repeated Falcon device metadata")
                    }

                    val hostname = firstNonBlank(
                        device["hostname"]?.toString(),
                        device["host_name"]?.toString(),
                        nestedDevice?.get("hostname")?.toString(),
                        (device["system"] as? Map<*, *>)?.get("hostname")?.toString()
                    )

                    val ipAddresses = collectIpAddresses(device, nestedDevice)
                        .take(MAX_IP_ADDRESSES_PER_DEVICE)
                        .toSortedSet()
                    val ip = firstNonBlank(
                        device["local_ip"]?.toString(),
                        nestedDevice?.get("local_ip")?.toString(),
                        ipAddresses.firstOrNull()
                    )

                    // Extract Active Directory domain (Feature 043)
                    val adDomain = firstNonBlank(
                        device["machine_domain"]?.toString(),
                        nestedDevice?.get("machine_domain")?.toString()
                    )

                    val cloudAccountId = firstNonBlank(
                        device["service_provider_account_id"]?.toString(),
                        nestedDevice?.get("service_provider_account_id")?.toString()
                    )

                    val cloudInstanceId = firstNonBlank(
                        device["instance_id"]?.toString(),
                        nestedDevice?.get("instance_id")?.toString()
                    )

                    // Operating system: os_version is the detailed string
                    // (e.g. "Windows Server 2019"); platform_name is a coarse fallback.
                    val osVersion = firstNonBlank(
                        device["os_version"]?.toString(),
                        nestedDevice?.get("os_version")?.toString(),
                        device["platform_name"]?.toString(),
                        nestedDevice?.get("platform_name")?.toString()
                    )

                    metadataByDeviceId[deviceId] = DeviceMetadata(
                        hostname = hostname,
                        ip = ip,
                        ipAddresses = ipAddresses,
                        adDomain = adDomain,
                        cloudAccountId = cloudAccountId,
                        cloudInstanceId = cloudInstanceId,
                        osVersion = osVersion,
                        lastSeen = device["last_seen"]?.toString()?.let { runCatching { Instant.parse(it) }.getOrNull() },
                        firstSeen = device["first_seen"]?.toString()?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    )
                }
            } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
                when (e.status.code) {
                    404 -> log.warn("Device metadata endpoint returned 404 for chunk of {} devices", chunk.size)
                    429 -> {
                        val retryAfter = e.response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                        log.warn("Rate limit retrieving device metadata for {} device IDs (retry after {}s)", chunk.size, retryAfter)
                    }
                    in 500..599 -> log.warn("CrowdStrike metadata endpoint server error {} for {} device IDs", e.status.code, chunk.size)
                    else -> log.warn("CrowdStrike metadata endpoint error {} retrieving device metadata: {}", e.status.code, e.message)
                }
            } catch (e: CrowdStrikeException) {
                throw e
            } catch (e: Exception) {
                log.warn("Unexpected error retrieving device metadata for {} device IDs: {}", chunk.size, e.message)
            }
        }

        if (metadataByDeviceId.isEmpty()) {
            log.debug("No hostname metadata resolved for {} device IDs", deviceIds.size)
        } else {
            log.debug("Resolved hostname metadata for {}/{} device IDs", metadataByDeviceId.size, deviceIds.size)
        }

        return metadataByDeviceId
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }

    private companion object {
        const val MAX_IP_ADDRESSES_PER_DEVICE = 100
    }

    /** Falcon device entities expose current addresses as scalar fields and may also return arrays. */
    private fun collectIpAddresses(vararg sources: Map<*, *>?): Set<String> =
        sources.filterNotNull()
            .flatMap { source ->
                listOf("local_ip", "external_ip", "connection_ip", "ip").flatMap { field ->
                    when (val value = source[field]) {
                        is Collection<*> -> value.mapNotNull { it?.toString() }
                        null -> emptyList()
                        else -> listOf(value.toString())
                    }
                }
            }
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSortedSet()

    /**
     * Map CrowdStrike API response to DTOs (bulk query version)
     * For bulk queries, we need to extract hostname from the API response
     *
     * @param resources Raw vulnerability resources from API
     * @return List of CrowdStrikeVulnerabilityDto
     */
    private fun mapResponseToDtos(
        resources: List<*>,
        metadataByDeviceId: Map<String, DeviceMetadata> = emptyMap()
    ): List<CrowdStrikeVulnerabilityDto> {
        return resources.mapNotNull { resource ->
            val vuln = resource as? Map<*, *> ?: return@mapNotNull null
            try {
                val id = vuln["id"]?.toString() ?: "cs-${System.currentTimeMillis()}"
                val hostInfo = vuln["host_info"] as? Map<*, *>
                val deviceInfo = vuln["device"] as? Map<*, *>

                val deviceId = firstNonBlank(
                    vuln["aid"]?.toString(),
                    vuln["device_id"]?.toString(),
                    deviceInfo?.get("device_id")?.toString(),
                    hostInfo?.get("device_id")?.toString()
                )

                val metadata = deviceId?.let { metadataByDeviceId[it] }

                val hostname = firstNonBlank(
                    metadata?.hostname,
                    hostInfo?.get("hostname")?.toString(),
                    hostInfo?.get("host_name")?.toString(),
                    vuln["hostname"]?.toString(),
                    deviceInfo?.get("hostname")?.toString(),
                    deviceInfo?.get("host_name")?.toString()
                ) ?: deviceId?.let { "[DEVICE:$it]" } ?: "[UNKNOWN]"

                val ip = firstNonBlank(
                    metadata?.ip,
                    vuln["local_ip"]?.toString(),
                    hostInfo?.get("local_ip")?.toString(),
                    deviceInfo?.get("local_ip")?.toString()
                )
                val ipAddresses = buildSet {
                    // Current device metadata takes precedence over older Spotlight host snapshots.
                    if (!metadata?.ipAddresses.isNullOrEmpty()) {
                        addAll(metadata!!.ipAddresses)
                    } else {
                        listOf(vuln, hostInfo, deviceInfo).filterNotNull().forEach { source ->
                            addAll(collectIpAddresses(source))
                        }
                    }
                    ip?.let(::add)
                }

                // Extract Active Directory domain (Feature 043)
                val adDomain = firstNonBlank(
                    metadata?.adDomain,
                    hostInfo?.get("machine_domain")?.toString(),
                    vuln["machine_domain"]?.toString()
                )

                val cloudAccountId = firstNonBlank(
                    metadata?.cloudAccountId,
                    hostInfo?.get("service_provider_account_id")?.toString(),
                    vuln["service_provider_account_id"]?.toString()
                )

                val cloudInstanceId = firstNonBlank(
                    metadata?.cloudInstanceId,
                    hostInfo?.get("instance_id")?.toString(),
                    vuln["instance_id"]?.toString()
                )

                // Operating system: prefer the resolved device-entity value,
                // fall back to host_info on the vulnerability record.
                val osVersion = firstNonBlank(
                    metadata?.osVersion,
                    hostInfo?.get("os_version")?.toString(),
                    hostInfo?.get("platform")?.toString()
                )

                val cveObject = vuln["cve"] as? Map<*, *>
                val cveId = cveObject?.get("id")?.toString()
                val cvssScore = (vuln["score"] as? Number)?.toDouble()
                    ?: (cveObject?.get("base_score") as? Number)?.toDouble()

                val apiSeverity = cveObject?.get("severity")?.toString()
                    ?: vuln["severity"]?.toString()
                    ?: vuln["cve_severity"]?.toString()

                val severity = if (!apiSeverity.isNullOrBlank()) {
                    normalizeSeverity(apiSeverity)
                } else if (cvssScore != null) {
                    mapCvssToSeverity(cvssScore)
                } else {
                    "Medium"
                }

                val apps = vuln["apps"] as? List<*>
                val affectedProduct = apps?.mapNotNull { app ->
                    (app as? Map<*, *>)?.get("product_name_version")?.toString()
                }?.joinToString(", ")

                val createdTimestamp = vuln["created_timestamp"]?.toString()
                    ?: vuln["created_on"]?.toString()
                // Shared with the ad-hoc lookup path so the two cannot drift (see FalconTimestamps).
                // Deliberately NOT coerced to now(): an unknown detection date stays
                // unknown, so the row reads "unknown age" rather than "zero days old"
                // (which would silently never be overdue). See FalconTimestamps.
                val detectedAt = FalconTimestamps.parse(createdTimestamp)
                if (detectedAt == null && !createdTimestamp.isNullOrBlank()) {
                    log.warn(
                        "Unparseable created_timestamp '{}' — reporting unknown detection date",
                        FalconTimestamps.sanitizeForLog(createdTimestamp)
                    )
                }

                // Extract patch publication date
                val patchPublicationDate = extractPatchPublicationDate(vuln)

                CrowdStrikeVulnerabilityDto(
                    id = id,
                    hostname = hostname,
                    ip = ip,
                    ipAddresses = ipAddresses,
                    adDomain = adDomain,  // Feature 043
                    osVersion = osVersion,
                    cveId = cveId,
                    severity = severity,
                    cvssScore = cvssScore,
                    affectedProduct = affectedProduct,
                    daysOpen = calculateDaysOpen(detectedAt),
                    detectedAt = detectedAt,
                    patchPublicationDate = patchPublicationDate,
                    cvePublishedDate = extractCvePublishedDate(vuln),
                    status = vuln["status"]?.toString() ?: "open",
                    hasException = false,
                    exceptionReason = null,
                    cloudAccountId = cloudAccountId,
                    cloudInstanceId = cloudInstanceId,
                    crowdStrikeAid = deviceId
                )
            } catch (e: Exception) {
                log.error("Failed to map vulnerability: {}", e.message, e)
                null
            }
        }
    }

    /**
     * Query vulnerabilities by AWS EC2 Instance ID
     *
     * Three-step workflow:
     * 1. Query devices by instance_id filter
     * 2. Get device details (hostname, metadata)
     * 3. Query vulnerabilities for each device
     *
     * @param instanceId AWS EC2 Instance ID (format: i-XXXXXXXXX...)
     * @param config CrowdStrike Falcon configuration
     * @return CrowdStrikeQueryResponse with aggregated vulnerabilities
     * @throws NotFoundException if instance ID not found
     * @throws RateLimitException if rate limit exceeded
     * @throws CrowdStrikeException for other API errors
     */
    override fun queryVulnerabilitiesByInstanceId(instanceId: String, config: FalconConfigDto): CrowdStrikeQueryResponse {
        require(instanceId.isNotBlank()) { "Instance ID cannot be blank" }
        require(instanceId.startsWith("i-", ignoreCase = true)) { "Instance ID must start with 'i-'" }

        log.info("Querying CrowdStrike by AWS instance ID: instanceId={}", instanceId)

        return try {
            // Step 1: Authenticate
            val token = getAuthToken(config)

            // Step 2: Query devices by instance ID
            val deviceIds = queryDeviceIdsByInstanceId(instanceId, token)

            if (deviceIds.isEmpty()) {
                throw NotFoundException("System not found with instance ID: $instanceId")
            }

            log.info("Found {} device(s) with instance ID '{}'", deviceIds.size, instanceId)

            val metadata = selectImportDevices(deviceIds, resolveImportMetadata(deviceIds, config))
            if (metadata.size != 1) throw CrowdStrikeException("Instance does not resolve to one unambiguous Falcon hostname")
            val selectedIds = metadata.keys.toList()
            val primaryHostname = metadata.values.single().hostname ?: instanceId

            // Step 4: Query vulnerabilities for each device
            val allVulnerabilities = mutableListOf<CrowdStrikeVulnerabilityDto>()
            val failedAids = mutableSetOf<String>()

            selectedIds.forEach { deviceId ->
                try {
                    val hostname = primaryHostname

                    val vulns = querySpotlightApi(deviceId, hostname, token)
                    allVulnerabilities.addAll(vulns)
                } catch (e: Exception) {
                    failedAids.add(deviceId)
                    log.warn("Failed to query vulnerabilities for device {}: {}", deviceId, e.message)
                }
            }

            log.info("Successfully queried CrowdStrike: instanceId={}, devices={}, vulnerabilities={}",
                instanceId, selectedIds.size, allVulnerabilities.size)

            CrowdStrikeQueryResponse(
                hostname = primaryHostname,
                instanceId = instanceId,
                failedAids = failedAids,
                devices = metadata.map { (id, md) -> md.toQueriedHost(id) }.toSet(),
                deviceCount = selectedIds.size,
                vulnerabilities = allVulnerabilities,
                totalCount = allVulnerabilities.size,
                queriedAt = LocalDateTime.now()
            )
        } catch (e: CrowdStrikeException) {
            log.error("CrowdStrike query failed: instanceId={}, error={}", instanceId, e.message)
            throw e
        } catch (e: Exception) {
            log.error("Unexpected error querying CrowdStrike by instance ID: instanceId={}", instanceId, e)
            throw CrowdStrikeException("Failed to query vulnerabilities for instance ID $instanceId: ${e.message}", e)
        }
    }

    /**
     * Query device IDs by AWS instance ID filter
     *
     * @param instanceId AWS EC2 Instance ID
     * @param token OAuth2 access token
     * @return List of device IDs (AIDs) with this instance ID
     */
    private fun queryDeviceIdsByInstanceId(instanceId: String, token: AuthToken): List<String> {
        require(instanceId.matches(Regex("i-[A-Za-z0-9]+"))) { "Invalid instance ID" }
        return queryAllMatchingDeviceIds("instance_id:'$instanceId'", token)
    }

    /**
     * Get device details by device IDs
     *
     * @param deviceIds List of device IDs (AIDs)
     * @param token OAuth2 access token
     * @return List of device detail maps with hostname, instance_id, etc.
     */
    /**
     * Query vulnerabilities by Active Directory domains
     *
     * Workflow:
     * 1. Authenticate with CrowdStrike
     * 2. Query devices by machine_domain filter for each domain
     * 3. Get vulnerabilities for all found devices
     * 4. Aggregate and return results
     *
     * @param domains List of AD domain names (case-insensitive)
     * @param severity Severity filter (e.g., "HIGH,CRITICAL")
     * @param minDaysOpen Minimum days open filter
     * @param config CrowdStrike Falcon configuration
     * @param limit Page size for pagination
     * @return CrowdStrikeQueryResponse with vulnerabilities from all devices in these domains
     */
    override fun queryVulnerabilitiesByDomains(
        domains: List<String>,
        severity: String,
        minDaysOpen: Int,
        config: FalconConfigDto,
        limit: Int
    ): CrowdStrikeQueryResponse {
        require(domains.isNotEmpty()) { "At least one domain must be provided" }

        log.info("Querying CrowdStrike by AD domains: domains={}, severity={}, minDaysOpen={}",
            domains.joinToString(","), severity, minDaysOpen)

        return try {
            // Step 1: Authenticate
            val token = getAuthToken(config)

            // Step 2: Query devices by domains
            val deviceIds = mutableSetOf<String>()
            domains.forEach { domain ->
                val domainDeviceIds = queryDeviceIdsByDomain(domain, token)
                deviceIds.addAll(domainDeviceIds)
                log.info("Found {} device(s) in domain '{}'", domainDeviceIds.size, domain)
            }

            if (deviceIds.isEmpty()) {
                log.info("No devices found in domains: {}", domains.joinToString(", "))
                return CrowdStrikeQueryResponse(
                    hostname = "DOMAINS: ${domains.joinToString(", ")}",
                    vulnerabilities = emptyList(),
                    totalCount = 0,
                    queriedAt = LocalDateTime.now()
                )
            }

            log.info("Found {} total device(s) across {} domain(s)", deviceIds.size, domains.size)

            val outcome = queryVulnerabilitiesByDeviceIdsDetailed(deviceIds.toList(), severity, minDaysOpen, config, limit)
            return CrowdStrikeQueryResponse(
                hostname = "DOMAINS: ${domains.joinToString(", ")}",
                deviceCount = outcome.devices.size,
                devices = outcome.devices,
                failedAids = outcome.failedDeviceIds,
                vulnerabilities = outcome.vulnerabilities,
                totalCount = outcome.vulnerabilities.size,
                queriedAt = LocalDateTime.now()
            )
        } catch (e: CrowdStrikeException) {
            log.error("CrowdStrike query failed: domains={}, error={}", domains.joinToString(","), e.message)
            throw e
        } catch (e: Exception) {
            log.error("Unexpected error querying CrowdStrike by domains: domains={}", domains.joinToString(","), e)
            throw CrowdStrikeException("Failed to query vulnerabilities for domains ${domains.joinToString(",")}: ${e.message}", e)
        }
    }

    /**
     * Query device IDs by AD domain filter
     *
     * Uses FQL filter: machine_domain:'DOMAIN' to find devices in a specific AD domain
     *
     * @param domain AD domain name (e.g., "CONTOSO")
     * @param token OAuth2 access token
     * @return List of device IDs (AIDs) in this domain
     */
    @Retryable(
        includes = [RateLimitException::class],
        attempts = "5",
        delay = "1s",
        multiplier = "2.0",
        maxDelay = "60s"
    )
    open fun queryDeviceIdsByDomain(domain: String, token: AuthToken): List<String> {
        require(domain.matches(Regex("[A-Za-z0-9_.-]{1,255}"))) { "Invalid AD domain" }
        return queryAllMatchingDeviceIds("machine_domain:'$domain'", token, 4000)
    }
}
