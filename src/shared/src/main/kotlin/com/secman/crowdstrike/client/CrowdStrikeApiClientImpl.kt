package com.secman.crowdstrike.client

import com.secman.crowdstrike.auth.CrowdStrikeAuthService
import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.exception.CrowdStrikeException
import com.secman.crowdstrike.exception.NotFoundException
import com.secman.crowdstrike.exception.RateLimitException
import com.secman.crowdstrike.model.AuthToken
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.uri.UriBuilder
import io.micronaut.retry.annotation.Retryable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
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
 *
 * Related to: Feature 023-create-in-the (CrowdStrike CLI)
 * Tasks: T029-T033
 */
@Singleton
open class CrowdStrikeApiClientImpl(
    @Client("https://api.crowdstrike.com")
    private val httpClient: HttpClient,
    private val authService: CrowdStrikeAuthService
) : CrowdStrikeApiClient {

    private val log = LoggerFactory.getLogger(CrowdStrikeApiClientImpl::class.java)

    @field:Value("\${secman.crowdstrike.batch-size:20}")
    protected var configuredBatchSize: Int = 20

    @field:Value("\${secman.crowdstrike.max-parallel-batches:4}")
    protected var configuredMaxParallelBatches: Int = 4

    /**
     * Query vulnerabilities for a specific hostname
     *
     * Task: T030
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

            // Get device ID from hostname
            val deviceId = getDeviceIdByHostname(hostname, token)
                ?: throw NotFoundException("Hostname not found in CrowdStrike: $hostname")

            log.info("Using device ID '{}' for hostname '{}'", deviceId, hostname)

            // Query Spotlight API - pass hostname so it can be included in results
            val vulnerabilityDtos = querySpotlightApi(deviceId, hostname, token)

            log.info("Successfully queried CrowdStrike: hostname={}, count={}", hostname, vulnerabilityDtos.size)

            CrowdStrikeQueryResponse(
                hostname = hostname,
                vulnerabilities = vulnerabilityDtos,
                totalCount = vulnerabilityDtos.size,
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
     * Query all vulnerabilities with automatic pagination
     *
     * Task: T032
     *
     * @param hostname System hostname to query
     * @param config CrowdStrike configuration
     * @param limit Page size (default: 100)
     * @return CrowdStrikeQueryResponse with all vulnerabilities
     */
    override fun queryAllVulnerabilities(
        hostname: String,
        config: FalconConfigDto,
        limit: Int
    ): CrowdStrikeQueryResponse {
        require(limit in 1..5000) { "Limit must be between 1 and 5000" }

        log.info("Querying CrowdStrike for all vulnerabilities (paginated): hostname={}, limit={}", hostname, limit)

        var allVulnerabilities = mutableListOf<CrowdStrikeVulnerabilityDto>()
        var offset = 0
        var hasMore = true

        while (hasMore) {
            try {
                val response = queryVulnerabilities(hostname, config)
                allVulnerabilities.addAll(response.vulnerabilities)

                // For now, return single page (pagination requires CrowdStrike cursor/offset support)
                hasMore = false
            } catch (e: CrowdStrikeException) {
                // Don't log stack trace for known CrowdStrike exceptions - just rethrow
                throw e
            } catch (e: Exception) {
                log.error("Error during pagination: offset={}", offset, e)
                throw e
            }
        }

        return CrowdStrikeQueryResponse(
            hostname = hostname,
            vulnerabilities = allVulnerabilities,
            totalCount = allVulnerabilities.size,
            queriedAt = LocalDateTime.now()
        )
    }

    /**
     * Query all vulnerabilities in bulk using server-side FQL filters
     *
     * Feature: 032-servers-query-import (Performance Optimization)
     * Optimization: Single API call instead of N device queries
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
        log.info("Bulk querying vulnerabilities: severity={}, minDaysOpen={}, deviceType={}",
            severity, minDaysOpen, deviceType)

        val token = getAuthToken(config)
        val allVulnerabilities = mutableListOf<CrowdStrikeVulnerabilityDto>()
        var afterToken: String? = null
        var hasMore = true
        var pageCount = 0

        // Build FQL filter - SIMPLIFIED for maximum compatibility
        // Only use status and severity filters (most reliable)
        // All other filtering (days open, device type) done client-side
        val severityFilter = buildSeverityFilter(severity)

        // Build complete FQL filter - MINIMAL for reliability
        val fqlFilter = "status:'open'+$severityFilter"

        log.info("FQL Filter: {} (days open and device type filtering: client-side)", fqlFilter)

        while (hasMore) {
            try {
                pageCount++
                log.debug("Bulk query page {}: limit={}, afterToken={}", pageCount, limit, afterToken ?: "null")

                val uri = UriBuilder.of("/spotlight/combined/vulnerabilities/v1")
                    .queryParam("filter", fqlFilter)
                    .queryParam("limit", limit.coerceAtMost(5000))
                    // Request CVE facet so severity/CVSS fields are present in the response
                    .queryParam("facet", "cve")
                    .apply {
                        if (afterToken != null) {
                            queryParam("after", afterToken)
                        }
                    }
                    .build()

                val request = HttpRequest.GET<Any>(uri.toString())
                    .header("Authorization", "Bearer ${token.accessToken}")
                    .header("Accept", "application/json")

                val response = httpClient.toBlocking().exchange(request, Map::class.java)

                when (response.status.code) {
                    200 -> {
                        @Suppress("UNCHECKED_CAST")
                        val responseBody = response.body() as? Map<String, Any>
                            ?: throw CrowdStrikeException("Empty response from Spotlight API")

                        val resources = responseBody["resources"] as? List<*> ?: emptyList<Any>()
                        val vulns = mapResponseToDtos(resources)

                        // Apply client-side filtering (days open + device type)
                        val filtered = vulns.filter { vuln ->
                            // Filter by days open
                            val daysOpenValue = vuln.daysOpen?.split(" ")?.firstOrNull()?.toIntOrNull() ?: 0
                            val meetsMinDaysOpen = daysOpenValue >= minDaysOpen

                            // Filter by device type (only if SERVER specified)
                            val meetsDeviceType = if (deviceType.equals("SERVER", ignoreCase = true)) {
                                val hostname = vuln.hostname?.lowercase() ?: ""
                                val isWorkstation = hostname.contains("laptop") ||
                                    hostname.contains("desktop") ||
                                    hostname.contains("workstation") ||
                                    hostname.contains("-pc") ||
                                    hostname.contains("client")
                                !isWorkstation  // Include if NOT a workstation
                            } else {
                                true  // No device type filtering
                            }

                            meetsMinDaysOpen && meetsDeviceType
                        }

                        allVulnerabilities.addAll(filtered)

                        log.info("Bulk query page {}: retrieved {} vulnerabilities, {} after filtering (total: {})",
                            pageCount, vulns.size, filtered.size, allVulnerabilities.size)

                        // Check for pagination
                        val meta = responseBody["meta"] as? Map<*, *>
                        val pagination = meta?.get("pagination") as? Map<*, *>
                        afterToken = pagination?.get("after")?.toString()

                        hasMore = afterToken != null && vulns.isNotEmpty()

                        if (hasMore) {
                            log.debug("More results available, fetching next page...")
                        }
                    }
                    404 -> {
                        log.info("Spotlight API returned 404. No vulnerabilities found.")
                        hasMore = false
                    }
                    429 -> {
                        val retryAfter = response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                        throw RateLimitException("Rate limit exceeded on bulk query", retryAfter)
                    }
                    in 500..599 -> throw CrowdStrikeException("Spotlight API server error: ${response.status}")
                    else -> throw CrowdStrikeException("Unexpected Spotlight API response: ${response.status}")
                }
            } catch (e: java.net.SocketTimeoutException) {
                log.error("Socket timeout during bulk query on page {}", pageCount)
                log.error("  FQL Filter: {}", fqlFilter)
                log.error("  Current limit: {}, afterToken: {}", limit, afterToken ?: "null")
                log.error("  Consider reducing page size or increasing HTTP client timeout")
                throw CrowdStrikeException("Timeout during bulk query (page $pageCount): ${e.message}", e)
            } catch (e: io.netty.channel.ChannelException) {
                log.error("Netty channel error during bulk query on page {}", pageCount)
                log.error("  FQL filter that caused error: {}", fqlFilter)
                log.error("  Page: {}, limit: {}, afterToken: {}", pageCount, limit, afterToken ?: "null")
                log.error("  The connection was closed unexpectedly by the server")
                throw CrowdStrikeException("Channel error during bulk query: ${e.message}", e)
            } catch (e: io.micronaut.http.client.exceptions.HttpClientException) {
                // Handle "Channel closed while aggregating message" errors
                if (e.message?.contains("Channel closed") == true ||
                    e.message?.contains("aggregating") == true) {
                    log.error("HTTP client channel error during bulk query on page {}", pageCount)
                    log.error("  Error message: {}", e.message)
                    log.error("  FQL filter: {}", fqlFilter)
                    log.error("  Page: {}, limit: {}, afterToken: {}", pageCount, limit, afterToken ?: "null")

                    // Retry with smaller page size if this is the first page
                    if (pageCount == 1 && limit > 100) {
                        log.warn("First page failed with limit={}. Retrying with limit=100", limit)
                        return queryAllVulnerabilitiesBulk(
                            severity = severity,
                            minDaysOpen = minDaysOpen,
                            deviceType = deviceType,
                            config = config,
                            limit = 100  // Retry with much smaller page size
                        )
                    }
                    throw CrowdStrikeException("Channel closed during bulk query: ${e.message}", e)
                }
                throw e
            } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
                when (e.status.code) {
                    404 -> {
                        log.info("Spotlight API returned 404. No vulnerabilities found.")
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
                log.error("Unexpected error during bulk query on page {}", pageCount, e)
                log.error("  FQL Filter: {}", fqlFilter)
                log.error("  Limit: {}, afterToken: {}", limit, afterToken ?: "null")
                throw CrowdStrikeException("Failed to execute bulk query: ${e.message}", e)
            }
        }

        log.info("Bulk query completed: {} total vulnerabilities retrieved in {} pages",
            allVulnerabilities.size, pageCount)

        return CrowdStrikeQueryResponse(
            hostname = "BULK_QUERY",
            vulnerabilities = allVulnerabilities,
            totalCount = allVulnerabilities.size,
            queriedAt = LocalDateTime.now()
        )
    }

    /**
     * Query servers with filters (device type, severity, days open)
     *
     * Feature: 032-servers-query-import
     * Task: T007, T008
     * Spec reference: FR-002, FR-003, FR-004, FR-001a
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
        limit: Int
    ): CrowdStrikeQueryResponse {
        log.info("Querying CrowdStrike servers: hostnames={}, deviceType={}, severity={}, minDaysOpen={}",
            hostnames?.joinToString(",") ?: "ALL", deviceType, severity, minDaysOpen)

        val allVulnerabilities = mutableListOf<CrowdStrikeVulnerabilityDto>()

        // If specific hostnames provided, query each one
        if (!hostnames.isNullOrEmpty()) {
            hostnames.forEach { hostname ->
                try {
                    val response = queryVulnerabilities(hostname, config)
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
                    log.warn("Hostname '{}' not found in CrowdStrike, skipping", hostname)
                } catch (e: Exception) {
                    log.error("Error querying hostname '{}'", hostname, e)
                    throw e
                }
            }
        } else {
            // TWO-STAGE OPTIMIZED QUERY:
            // 1. Query SERVER devices first (using product_type_desc filter)
            // 2. Query vulnerabilities only for those specific devices
            // This avoids querying ALL vulnerabilities (which caused 30-minute timeouts)
            log.info("Querying all servers using two-stage optimization (servers first, then vulnerabilities)")

            // Stage 1: Get SERVER device IDs
            log.info(">>> Stage 1: Getting authentication token")
            val token = getAuthToken(config)
            log.info(">>> Stage 1: Querying SERVER devices with product_type_desc filter")
            val serverDeviceIds = getServerDeviceIdsFiltered(token, limit)

            if (serverDeviceIds.isEmpty()) {
                log.info(">>> Stage 1: No SERVER devices found in CrowdStrike")
                return CrowdStrikeQueryResponse(
                    hostname = "ALL",
                    vulnerabilities = emptyList(),
                    totalCount = 0,
                    queriedAt = LocalDateTime.now()
                )
            }

            log.info(">>> Stage 1 complete: Found {} SERVER devices", serverDeviceIds.size)
            log.info(">>> Stage 1: Sample device IDs: {}", serverDeviceIds.take(10).joinToString(", "))

            // Stage 2: Query vulnerabilities for those specific devices
            log.info(">>> Stage 2: Starting vulnerability query for {} devices", serverDeviceIds.size)
            val vulnerabilities = queryVulnerabilitiesByDeviceIds(
                deviceIds = serverDeviceIds,
                severity = severity,
                minDaysOpen = minDaysOpen,
                config = config,  // Pass config instead of token so fresh token can be obtained
                limit = limit
            )

            allVulnerabilities.addAll(vulnerabilities)

            log.info("Stage 2 complete: {} vulnerabilities found across {} servers",
                vulnerabilities.size, serverDeviceIds.size)
        }

        return CrowdStrikeQueryResponse(
            hostname = hostnames?.joinToString(",") ?: "ALL",
            vulnerabilities = allVulnerabilities,
            totalCount = allVulnerabilities.size,
            queriedAt = LocalDateTime.now()
        )
    }

    /**
     * Get all server device IDs from CrowdStrike
     *
     * Feature: 032-servers-query-import
     * Task: T007
     * Spec reference: FR-001
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
     * Get SERVER device IDs from CrowdStrike using product_type_desc filter
     *
     * Feature: 032-servers-query-import (Performance Optimization)
     * Optimization: Query only SERVER devices (not workstations) using product_type_desc filter
     *
     * @param token OAuth2 access token
     * @param limit Maximum number of devices to retrieve per page
     * @return List of server device IDs
     */
    @Retryable(
        includes = [RateLimitException::class],
        attempts = "5",
        delay = "1s",
        multiplier = "2.0",
        maxDelay = "60s"
    )
    open fun getServerDeviceIdsFiltered(
        token: AuthToken,
        limit: Int = 5000
    ): List<String> {
        log.info(">>> Stage 1: Querying SERVER devices with product_type_desc + last_seen filters")

        val allDeviceIds = mutableListOf<String>()
        var offset = 0
        var hasMore = true

        while (hasMore) {
            try {
                // OPTIMIZATION: Combine filters to get only active servers seen in the last day
                // - product_type_desc:'Server' = Only servers (not workstations)
                // - last_seen:>'now-1d' = Only devices seen in the last 24 hours
                // This reduces the result set from ~17,700 to <3,000 servers
                val filter = "product_type_desc:'Server'+last_seen:>'now-1d'"

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

                        val resources = responseBody["resources"] as? List<*> ?: emptyList<Any>()
                        val deviceIds = resources.mapNotNull { it?.toString() }

                        allDeviceIds.addAll(deviceIds)

                        // Check if there are more pages
                        val meta = responseBody["meta"] as? Map<*, *>
                        val pagination = meta?.get("pagination") as? Map<*, *>
                        val total = (pagination?.get("total") as? Number)?.toInt() ?: deviceIds.size

                        log.info(">>> Stage 1 (page {}): Retrieved {} device IDs (total so far: {}, total available: {})",
                            (offset / limit) + 1, deviceIds.size, allDeviceIds.size, total)

                        hasMore = allDeviceIds.size < total && deviceIds.isNotEmpty()
                        offset += deviceIds.size

                        if (hasMore) {
                            log.info(">>> Stage 1: More devices available, continuing pagination (offset: {})", offset)
                        }
                    }
                    404 -> {
                        log.info("No SERVER devices found")
                        hasMore = false
                    }
                    429 -> {
                        val retryAfter = response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                        throw RateLimitException("Rate limit exceeded querying SERVER devices", retryAfter)
                    }
                    in 500..599 -> throw CrowdStrikeException("CrowdStrike server error: ${response.status}")
                    else -> throw CrowdStrikeException("Unexpected CrowdStrike response: ${response.status}")
                }
            } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
                when (e.status.code) {
                    404 -> {
                        log.info("No SERVER devices found")
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
                log.error("Unexpected error querying SERVER device IDs", e)
                throw CrowdStrikeException("Failed to query SERVER device IDs: ${e.message}", e)
            }
        }

        log.info(">>> Stage 1 complete: {} active SERVER devices found (last seen within 24 hours)", allDeviceIds.size)
        log.info(">>> Stage 1 complete: Optimization reduced server count from ~17,700 to {}", allDeviceIds.size)
        return allDeviceIds
    }

    /**
     * Query vulnerabilities for specific device IDs with filters
     *
     * Feature: 032-servers-query-import (Performance Optimization)
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
    ): List<CrowdStrikeVulnerabilityDto> {
        if (deviceIds.isEmpty()) {
            log.info("No device IDs provided, returning empty list")
            return emptyList()
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

        if (batches.size == 1) {
            allVulnerabilities.addAll(
                queryBatchVulnerabilities(
                    batchIndex = 0,
                    totalBatches = 1,
                    deviceIds = batches.first(),
                    severity = severity,
                    minDaysOpen = minDaysOpen,
                    limit = limit,
                    config = config
                )
            )
        } else {
            val executor = createBatchExecutor(parallelism)
            val futures = batches.mapIndexed { index, batch ->
                executor.submit(Callable {
                    queryBatchVulnerabilities(
                        batchIndex = index,
                        totalBatches = batches.size,
                        deviceIds = batch,
                        severity = severity,
                        minDaysOpen = minDaysOpen,
                        limit = limit,
                        config = config
                    )
                })
            }

            // Collect results with fault tolerance - continue even if some batches fail
            val failedBatches = mutableListOf<Int>()
            val errors = mutableListOf<String>()

            try {
                futures.forEachIndexed { index, future ->
                    try {
                        allVulnerabilities.addAll(future.get())
                    } catch (e: ExecutionException) {
                        val cause = e.cause
                        val errorMsg = "Batch ${index + 1}/${batches.size} failed: ${cause?.message ?: e.message}"
                        log.warn(">>> $errorMsg")
                        failedBatches.add(index + 1)
                        errors.add(errorMsg)
                        // Continue with other batches instead of failing entirely
                    } catch (e: java.util.concurrent.CancellationException) {
                        log.warn(">>> Batch ${index + 1}/${batches.size} was cancelled")
                        failedBatches.add(index + 1)
                    }
                }
            } catch (e: InterruptedException) {
                log.warn(">>> Batch processing interrupted, cancelling remaining futures")
                futures.forEach { it.cancel(true) }
                Thread.currentThread().interrupt()
                // Don't throw - return partial results
            } finally {
                executor.shutdown()
            }

            // Log summary of failures
            if (failedBatches.isNotEmpty()) {
                log.warn(">>> {} of {} batches failed: {}", failedBatches.size, batches.size, failedBatches.joinToString(", "))
                log.warn(">>> Continuing with {} vulnerabilities from successful batches", allVulnerabilities.size)
            }
        }

        log.info(">>> Stage 2 complete: {} total vulnerabilities found across {} batches",
            allVulnerabilities.size, batches.size)

        return allVulnerabilities
    }

    private fun queryBatchVulnerabilities(
        batchIndex: Int,
        totalBatches: Int,
        deviceIds: List<String>,
        severity: String,
        minDaysOpen: Int,
        limit: Int,
        config: FalconConfigDto
    ): List<CrowdStrikeVulnerabilityDto> {
        var token = getAuthToken(config)
        val metadataByDeviceId = resolveDeviceMetadata(deviceIds, token)
        val severityFilter = buildSeverityFilter(severity)
        val deviceIdFilter = buildDeviceIdFilter(deviceIds)
        val fqlFilter = "$deviceIdFilter+status:'open'+$severityFilter"
        val batchVulnerabilities = mutableListOf<CrowdStrikeVulnerabilityDto>()
        val effectiveLimit = limit.coerceAtMost(5000)
        val maxPagesPerBatch = 50
        var afterToken: String? = null
        var hasMore = true
        var pageCount = 0

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
                .queryParam("facet", "cve")
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

                        val resources = responseBody["resources"] as? List<*> ?: emptyList<Any>()
                        val vulns = mapResponseToDtos(resources, metadataByDeviceId)

                        val filtered = vulns.filter { vuln ->
                            val daysOpenValue = vuln.daysOpen?.split(" ")?.firstOrNull()?.toIntOrNull() ?: 0
                            daysOpenValue >= minDaysOpen
                        }

                        val meta = responseBody["meta"] as? Map<*, *>
                        val pagination = meta?.get("pagination") as? Map<*, *>
                        val prevAfterToken = afterToken
                        afterToken = pagination?.get("after")?.toString()

                        log.info(">>> Batch {}/{} page {}: Retrieved {} vulns, {} after filter (batch total: {})",
                            batchIndex + 1, totalBatches, pageCount, vulns.size, filtered.size,
                            batchVulnerabilities.size + filtered.size)
                        log.debug(">>> Batch {}/{} page {}: Pagination - afterToken: {}, vulns.isNotEmpty: {}",
                            batchIndex + 1, totalBatches, pageCount,
                            if (afterToken != null) "present" else "null",
                            vulns.isNotEmpty())

                        batchVulnerabilities.addAll(filtered)

                        hasMore = afterToken != null && vulns.isNotEmpty()

                        if (hasMore && afterToken == prevAfterToken) {
                            log.warn(">>> Batch {}/{} page {}: afterToken unchanged - breaking pagination loop",
                                batchIndex + 1, totalBatches, pageCount)
                            hasMore = false
                        }

                        if (hasMore && filtered.isEmpty() && vulns.isNotEmpty() && pageCount >= 3) {
                            log.warn(">>> Batch {}/{} page {}: Got {} vulns but ALL filtered out for {} pages - stopping pagination (minDaysOpen={} may be too strict)",
                                batchIndex + 1, totalBatches, pageCount, vulns.size, pageCount, minDaysOpen)
                            hasMore = false
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

        if (pageCount >= maxPagesPerBatch) {
            log.warn(">>> Batch {}/{}: Reached max page limit ({} pages) - stopping pagination for this batch",
                batchIndex + 1, totalBatches, maxPagesPerBatch)
        }

        log.debug(">>> Batch {}/{} complete: {} vulnerabilities collected",
            batchIndex + 1, totalBatches, batchVulnerabilities.size)

        return batchVulnerabilities
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
        log.debug("Looking up device ID for hostname: {}", hostname)

        // Strategy 1: Exact match (case-sensitive)
        log.debug("Strategy 1: Trying exact match")
        val exactResult = tryHostnameFilter(hostname, "hostname:'$hostname'", token)
        if (exactResult != null) {
            log.info("Found device ID using exact match: {}", exactResult)
            return exactResult
        }

        // Strategy 2: Stemmed search (case-insensitive, starts with)
        log.debug("Strategy 2: Trying stemmed search (starts with)")
        val stemmedResult = tryHostnameFilter(hostname, "hostname:'$hostname*'", token)
        if (stemmedResult != null) {
            log.info("Found device ID using stemmed search: {}", stemmedResult)
            return stemmedResult
        }

        // Strategy 3: Contains search (case-insensitive, wildcards inside quotes)
        log.debug("Strategy 3: Trying contains search")
        val containsResult = tryHostnameFilter(hostname, "hostname:'*$hostname*'", token)
        if (containsResult != null) {
            log.info("Found device ID using contains search: {}", containsResult)
            return containsResult
        }

        // Strategy 4: Lowercase exact match
        log.debug("Strategy 4: Trying lowercase exact match")
        val lowerExactResult = tryHostnameFilter(hostname, "hostname:'${hostname.lowercase()}'", token)
        if (lowerExactResult != null) {
            log.info("Found device ID using lowercase exact match: {}", lowerExactResult)
            return lowerExactResult
        }

        // Strategy 5: Uppercase exact match
        log.debug("Strategy 5: Trying uppercase exact match")
        val upperExactResult = tryHostnameFilter(hostname, "hostname:'${hostname.uppercase()}'", token)
        if (upperExactResult != null) {
            log.info("Found device ID using uppercase exact match: {}", upperExactResult)
            return upperExactResult
        }

        log.warn("All hostname strategies exhausted. No device found for: {}", hostname)
        return null
    }

    /**
     * Try a single hostname filter strategy
     *
     * @param hostname Original hostname (for logging)
     * @param filter FQL filter string
     * @param token OAuth2 access token
     * @return Device ID or null if not found
     * @throws RateLimitException if rate limit hit
     */
    private fun tryHostnameFilter(hostname: String, filter: String, token: AuthToken): String? = try {
        log.info("Trying filter for hostname '{}': {}", hostname, filter)
        val uri = UriBuilder.of("/devices/queries/devices/v1")
            .queryParam("filter", filter)
            .queryParam("limit", "10")
            .build()

        val request = HttpRequest.GET<Any>(uri.toString())
            .header("Authorization", "Bearer ${token.accessToken}")
            .header("Accept", "application/json")

        log.info("Making request to: {}", uri)
        val response = httpClient.toBlocking().exchange(request, Map::class.java)
        log.info("Response status: {}", response.status)

        when (response.status.code) {
            200 -> {
                @Suppress("UNCHECKED_CAST")
                val responseBody = response.body() as? Map<String, Any>
                    ?: throw CrowdStrikeException("Empty response from CrowdStrike Hosts API")

                val resources = responseBody["resources"] as? List<*>
                if (resources.isNullOrEmpty()) {
                    log.debug("Filter returned no results: {}", filter)
                    null
                } else {
                    val deviceId = resources[0]?.toString()
                    log.debug("Filter returned {} results. Using first: {}", resources.size, deviceId)
                    deviceId
                }
            }
            429 -> {
                val retryAfter = response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                throw RateLimitException("Rate limit during hostname lookup", retryAfter)
            }
            in 500..599 -> throw CrowdStrikeException("CrowdStrike server error: ${response.status}")
            else -> {
                log.debug("Filter failed: status={}, filter={}", response.status, filter)
                null
            }
        }
    } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
        when (e.status.code) {
            429 -> {
                val retryAfter = e.response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                throw RateLimitException("Rate limit during hostname lookup", retryAfter, e)
            }
            in 500..599 -> throw CrowdStrikeException("CrowdStrike server error: ${e.status}", e)
            else -> {
                log.debug("Filter failed: status={}", e.status)
                null
            }
        }
    } catch (e: RateLimitException) {
        throw e
    } catch (e: Exception) {
        log.error("Error querying hostname filter: {}", filter, e)
        null
    }

    /**
     * Query CrowdStrike Spotlight API for vulnerabilities
     *
     * Task: T030, T031 (with retry logic)
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

        return try {
            // Build filter query
            val filter = "aid:'$deviceId'+status:'open'"

            val uri = UriBuilder.of("/spotlight/combined/vulnerabilities/v1")
                .queryParam("filter", filter)
                .queryParam("limit", "5000")
                // Request CVE facet so severity/CVSS fields are present in the response
                .queryParam("facet", "cve")
                .build()

            val request = HttpRequest.GET<Any>(uri.toString())
                .header("Authorization", "Bearer ${token.accessToken}")
                .header("Accept", "application/json")

            log.debug("Spotlight API request: filter={}, uri={}", filter, uri)

            val response = httpClient.toBlocking().exchange(request, Map::class.java)

            when (response.status.code) {
                200 -> {
                    @Suppress("UNCHECKED_CAST")
                    val responseBody = response.body() as? Map<String, Any>
                        ?: throw CrowdStrikeException("Empty response from Spotlight API")

                    val resources = responseBody["resources"] as? List<*> ?: emptyList<Any>()
                    log.info("Spotlight API returned {} vulnerabilities for device {}", resources.size, deviceId)

                    // Task T033: Map responses to shared DTOs with hostname
                    mapResponseToDtos(resources, hostname)
                }
                404 -> {
                    log.info("Spotlight API returned 404 for device {}. Treating as no vulnerabilities.", deviceId)
                    emptyList()
                }
                429 -> {
                    val retryAfter = response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                    throw RateLimitException("Rate limit exceeded on Spotlight API", retryAfter)
                }
                in 500..599 -> throw CrowdStrikeException("Spotlight API server error: ${response.status}")
                else -> throw CrowdStrikeException("Unexpected Spotlight API response: ${response.status}")
            }
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            when (e.status.code) {
                404 -> {
                    log.info("Spotlight API returned 404 for device. Treating as no vulnerabilities.")
                    emptyList()
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
            log.error("Unexpected error querying Spotlight API", e)
            throw CrowdStrikeException("Failed to query Spotlight API: ${e.message}", e)
        }
    }

    /**
     * Map CrowdStrike API response to DTOs
     *
     * Task: T033
     *
     * @param resources Raw vulnerability resources from API
     * @param hostname Hostname for the device (from query context)
     * @return List of CrowdStrikeVulnerabilityDto
     */
    private fun mapResponseToDtos(resources: List<*>, hostname: String): List<CrowdStrikeVulnerabilityDto> {
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
                val detectedAt = if (createdTimestamp != null) {
                    try {
                        // Parse ISO-8601 timestamp with 'Z' timezone (e.g., "2024-05-15T22:18:26Z")
                        val instant = Instant.parse(createdTimestamp)
                        LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                    } catch (e: Exception) {
                        // Fallback: try parsing without timezone indicator
                        try {
                            LocalDateTime.parse(createdTimestamp.replace(" ", "T").replace("Z", ""))
                        } catch (e2: Exception) {
                            log.warn("Failed to parse created_timestamp '{}': {}", createdTimestamp, e2.message)
                            LocalDateTime.now()
                        }
                    }
                } else {
                    LocalDateTime.now()
                }

                // Extract patch publication date
                val patchPublicationDate = extractPatchPublicationDate(vuln)

                val dto = CrowdStrikeVulnerabilityDto(
                    id = id,
                    hostname = hostname,
                    ip = ip,
                    adDomain = adDomain,  // Feature 043
                    cveId = cveId,
                    severity = severity,
                    cvssScore = cvssScore,
                    affectedProduct = affectedProduct,
                    daysOpen = calculateDaysOpen(detectedAt),
                    detectedAt = detectedAt,
                    patchPublicationDate = patchPublicationDate,
                    status = vuln["status"]?.toString() ?: "open",
                    hasException = false,
                    exceptionReason = null
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
    private fun calculateDaysOpen(detectedAt: LocalDateTime): String {
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
    private fun extractPatchPublicationDate(vuln: Map<*, *>): LocalDateTime? {
        val cveObject = vuln["cve"] as? Map<*, *>
        val remediationObject = vuln["remediation"] as? Map<*, *>

        // Try multiple possible field locations
        val dateString = cveObject?.get("published_date")?.toString()
            ?: cveObject?.get("published")?.toString()
            ?: remediationObject?.get("published_date")?.toString()
            ?: remediationObject?.get("vendor_release_date")?.toString()
            ?: vuln["patch_published_date"]?.toString()
            ?: vuln["patch_publication_date"]?.toString()

        if (dateString == null) {
            return null
        }

        return try {
            // Try parsing as ISO-8601 timestamp (e.g., "2024-05-15T22:18:26Z")
            val instant = Instant.parse(dateString)
            LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        } catch (e: Exception) {
            try {
                // Fallback: try parsing as date-only (e.g., "2024-05-15")
                LocalDateTime.parse(dateString + "T00:00:00")
            } catch (e2: Exception) {
                try {
                    // Fallback: try parsing without timezone
                    LocalDateTime.parse(dateString.replace(" ", "T").replace("Z", ""))
                } catch (e3: Exception) {
                    log.debug("Failed to parse patch publication date '{}': {}", dateString, e3.message)
                    null
                }
            }
        }
    }

    private data class DeviceMetadata(
        val hostname: String?,
        val ip: String?,
        val adDomain: String?  // Feature 043: Active Directory domain
    )

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
                val resources = responseBody["resources"] as? List<*> ?: emptyList<Any>()

                resources.forEach { resource ->
                    val device = resource as? Map<*, *> ?: return@forEach
                    val nestedDevice = device["device"] as? Map<*, *>

                    val deviceId = firstNonBlank(
                        device["device_id"]?.toString(),
                        nestedDevice?.get("device_id")?.toString(),
                        device["id"]?.toString(),
                        device["aid"]?.toString()
                    )

                    if (deviceId.isNullOrBlank()) {
                        return@forEach
                    }

                    val hostname = firstNonBlank(
                        device["hostname"]?.toString(),
                        device["host_name"]?.toString(),
                        nestedDevice?.get("hostname")?.toString(),
                        (device["system"] as? Map<*, *>)?.get("hostname")?.toString()
                    )

                    val ip = firstNonBlank(
                        device["local_ip"]?.toString(),
                        nestedDevice?.get("local_ip")?.toString(),
                        (device["ip"] as? List<*>)?.firstOrNull()?.toString(),
                        (device["external_ip"] as? List<*>)?.firstOrNull()?.toString()
                    )

                    // Extract Active Directory domain (Feature 043)
                    val adDomain = firstNonBlank(
                        device["machine_domain"]?.toString(),
                        nestedDevice?.get("machine_domain")?.toString()
                    )

                    metadataByDeviceId[deviceId] = DeviceMetadata(
                        hostname = hostname,
                        ip = ip,
                        adDomain = adDomain
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

                // Extract Active Directory domain (Feature 043)
                val adDomain = firstNonBlank(
                    metadata?.adDomain,
                    hostInfo?.get("machine_domain")?.toString(),
                    vuln["machine_domain"]?.toString()
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
                val detectedAt = if (createdTimestamp != null) {
                    try {
                        val instant = Instant.parse(createdTimestamp)
                        LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                    } catch (e: Exception) {
                        try {
                            LocalDateTime.parse(createdTimestamp.replace(" ", "T").replace("Z", ""))
                        } catch (e2: Exception) {
                            LocalDateTime.now()
                        }
                    }
                } else {
                    LocalDateTime.now()
                }

                // Extract patch publication date
                val patchPublicationDate = extractPatchPublicationDate(vuln)

                CrowdStrikeVulnerabilityDto(
                    id = id,
                    hostname = hostname,
                    ip = ip,
                    adDomain = adDomain,  // Feature 043
                    cveId = cveId,
                    severity = severity,
                    cvssScore = cvssScore,
                    affectedProduct = affectedProduct,
                    daysOpen = calculateDaysOpen(detectedAt),
                    detectedAt = detectedAt,
                    patchPublicationDate = patchPublicationDate,
                    status = vuln["status"]?.toString() ?: "open",
                    hasException = false,
                    exceptionReason = null
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
     * Feature: 041-falcon-instance-lookup
     * Tasks: T011, T012, T013
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

            // Step 3: Get device details to extract hostnames
            val deviceDetails = getDeviceDetailsByIds(deviceIds, token)

            val hostnames = deviceDetails.mapNotNull { it["hostname"]?.toString() }
            val primaryHostname = hostnames.firstOrNull() ?: instanceId

            log.info("Device hostnames: {}", hostnames.joinToString(", "))

            // Step 4: Query vulnerabilities for each device
            val allVulnerabilities = mutableListOf<CrowdStrikeVulnerabilityDto>()

            deviceIds.forEach { deviceId ->
                try {
                    val hostname = deviceDetails.find { it["device_id"] == deviceId }
                        ?.get("hostname")?.toString() ?: instanceId

                    val vulns = querySpotlightApi(deviceId, hostname, token)
                    allVulnerabilities.addAll(vulns)
                } catch (e: Exception) {
                    log.warn("Failed to query vulnerabilities for device {}: {}", deviceId, e.message)
                }
            }

            log.info("Successfully queried CrowdStrike: instanceId={}, devices={}, vulnerabilities={}",
                instanceId, deviceIds.size, allVulnerabilities.size)

            CrowdStrikeQueryResponse(
                hostname = if (hostnames.size > 1) hostnames.joinToString(", ") else primaryHostname,
                instanceId = instanceId,
                deviceCount = deviceIds.size,
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
     * Feature: 041-falcon-instance-lookup
     * Task: T011
     *
     * @param instanceId AWS EC2 Instance ID
     * @param token OAuth2 access token
     * @return List of device IDs (AIDs) with this instance ID
     */
    private fun queryDeviceIdsByInstanceId(instanceId: String, token: AuthToken): List<String> {
        log.debug("Querying devices by instance ID: {}", instanceId)

        // Use FQL filter: instance_id:'i-xxx'
        val filter = "instance_id:'$instanceId'"

        val uri = UriBuilder.of("/devices/queries/devices/v1")
            .queryParam("filter", filter)
            .queryParam("limit", "100")
            .build()

        val request = HttpRequest.GET<Any>(uri.toString())
            .header("Authorization", "Bearer ${token.accessToken}")
            .header("Accept", "application/json")

        log.debug("Querying devices: filter={}", filter)

        return try {
            val response = httpClient.toBlocking().exchange(request, Map::class.java)

            when (response.status.code) {
                200 -> {
                    @Suppress("UNCHECKED_CAST")
                    val responseBody = response.body() as? Map<String, Any>
                        ?: throw CrowdStrikeException("Empty response from CrowdStrike Hosts API")

                    val resources = responseBody["resources"] as? List<*> ?: emptyList<Any>()
                    val deviceIds = resources.mapNotNull { it?.toString() }

                    log.info("Found {} device(s) with instance ID '{}'", deviceIds.size, instanceId)
                    deviceIds
                }
                404 -> {
                    log.debug("No devices found with instance ID: {}", instanceId)
                    emptyList()
                }
                429 -> {
                    val retryAfter = response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                    throw RateLimitException("Rate limit during instance ID lookup", retryAfter)
                }
                in 500..599 -> throw CrowdStrikeException("CrowdStrike server error: ${response.status}")
                else -> {
                    log.warn("Unexpected response for instance ID query: status={}", response.status)
                    emptyList()
                }
            }
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            when (e.status.code) {
                429 -> {
                    val retryAfter = e.response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                    throw RateLimitException("Rate limit during instance ID lookup", retryAfter, e)
                }
                404 -> {
                    log.debug("No devices found with instance ID: {}", instanceId)
                    emptyList()
                }
                else -> throw CrowdStrikeException("Failed to query devices by instance ID: ${e.message}", e)
            }
        }
    }

    /**
     * Get device details by device IDs
     *
     * Feature: 041-falcon-instance-lookup
     * Task: T012
     *
     * @param deviceIds List of device IDs (AIDs)
     * @param token OAuth2 access token
     * @return List of device detail maps with hostname, instance_id, etc.
     */
    private fun getDeviceDetailsByIds(deviceIds: List<String>, token: AuthToken): List<Map<String, Any>> {
        if (deviceIds.isEmpty()) {
            return emptyList()
        }

        log.debug("Getting device details for {} device(s)", deviceIds.size)

        val uri = UriBuilder.of("/devices/entities/devices/v1")
            .queryParam("ids", deviceIds.joinToString(","))
            .build()

        val request = HttpRequest.GET<Any>(uri.toString())
            .header("Authorization", "Bearer ${token.accessToken}")
            .header("Accept", "application/json")

        return try {
            val response = httpClient.toBlocking().exchange(request, Map::class.java)

            when (response.status.code) {
                200 -> {
                    @Suppress("UNCHECKED_CAST")
                    val responseBody = response.body() as? Map<String, Any>
                        ?: throw CrowdStrikeException("Empty response from CrowdStrike Device Details API")

                    val resources = responseBody["resources"] as? List<*> ?: emptyList<Any>()

                    @Suppress("UNCHECKED_CAST")
                    resources.mapNotNull { it as? Map<String, Any> }
                }
                429 -> {
                    val retryAfter = response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                    throw RateLimitException("Rate limit during device details lookup", retryAfter)
                }
                in 500..599 -> throw CrowdStrikeException("CrowdStrike server error: ${response.status}")
                else -> {
                    log.warn("Unexpected response for device details: status={}", response.status)
                    emptyList()
                }
            }
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            when (e.status.code) {
                429 -> {
                    val retryAfter = e.response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                    throw RateLimitException("Rate limit during device details lookup", retryAfter, e)
                }
                else -> throw CrowdStrikeException("Failed to get device details: ${e.message}", e)
            }
        }
    }

    /**
     * Query vulnerabilities by Active Directory domains
     *
     * Feature: 042-domain-vulnerabilities-view
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

            // Step 3: Query vulnerabilities for all devices
            val vulnerabilities = queryVulnerabilitiesByDeviceIds(
                deviceIds = deviceIds.toList(),
                severity = severity,
                minDaysOpen = minDaysOpen,
                config = config,
                limit = limit
            )

            log.info("Successfully queried CrowdStrike: domains={}, devices={}, vulnerabilities={}",
                domains.joinToString(","), deviceIds.size, vulnerabilities.size)

            CrowdStrikeQueryResponse(
                hostname = "DOMAINS: ${domains.joinToString(", ")}",
                deviceCount = deviceIds.size,
                vulnerabilities = vulnerabilities,
                totalCount = vulnerabilities.size,
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
     * Feature: 042-domain-vulnerabilities-view
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
        log.debug("Querying devices by AD domain: {}", domain)

        val allDeviceIds = mutableListOf<String>()
        var offset = 0
        val pageLimit = 5000
        var hasMore = true

        while (hasMore) {
            try {
                // Use FQL filter: machine_domain:'DOMAIN'
                // Note: machine_domain is case-insensitive in CrowdStrike
                val filter = "machine_domain:'$domain'"

                val uri = UriBuilder.of("/devices/queries/devices/v1")
                    .queryParam("filter", filter)
                    .queryParam("limit", pageLimit)
                    .queryParam("offset", offset)
                    .build()

                val request = HttpRequest.GET<Any>(uri.toString())
                    .header("Authorization", "Bearer ${token.accessToken}")
                    .header("Accept", "application/json")

                log.debug("Querying devices: filter={}, offset={}", filter, offset)

                val response = httpClient.toBlocking().exchange(request, Map::class.java)

                when (response.status.code) {
                    200 -> {
                        @Suppress("UNCHECKED_CAST")
                        val responseBody = response.body() as? Map<String, Any>
                            ?: throw CrowdStrikeException("Empty response from CrowdStrike Hosts API")

                        val resources = responseBody["resources"] as? List<*> ?: emptyList<Any>()
                        val deviceIds = resources.mapNotNull { it?.toString() }

                        allDeviceIds.addAll(deviceIds)

                        // Check if there are more pages
                        val meta = responseBody["meta"] as? Map<*, *>
                        val pagination = meta?.get("pagination") as? Map<*, *>
                        val total = (pagination?.get("total") as? Number)?.toInt() ?: deviceIds.size

                        hasMore = allDeviceIds.size < total && deviceIds.isNotEmpty()
                        offset += deviceIds.size

                        log.debug("Retrieved {} device IDs for domain '{}' (total: {})",
                            deviceIds.size, domain, allDeviceIds.size)

                        if (hasMore) {
                            log.debug("More devices available for domain '{}', continuing pagination (offset: {})",
                                domain, offset)
                        }
                    }
                    404 -> {
                        log.debug("No devices found for domain: {}", domain)
                        hasMore = false
                    }
                    429 -> {
                        val retryAfter = response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                        throw RateLimitException("Rate limit during domain lookup", retryAfter)
                    }
                    in 500..599 -> throw CrowdStrikeException("CrowdStrike server error: ${response.status}")
                    else -> {
                        log.warn("Unexpected response for domain query: status={}", response.status)
                        hasMore = false
                    }
                }
            } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
                when (e.status.code) {
                    429 -> {
                        val retryAfter = e.response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                        throw RateLimitException("Rate limit during domain lookup", retryAfter, e)
                    }
                    404 -> {
                        log.debug("No devices found for domain: {}", domain)
                        hasMore = false
                    }
                    else -> throw CrowdStrikeException("Failed to query devices by domain: ${e.message}", e)
                }
            } catch (e: RateLimitException) {
                throw e
            } catch (e: Exception) {
                log.error("Unexpected error querying devices by domain", e)
                throw CrowdStrikeException("Failed to query devices by domain: ${e.message}", e)
            }
        }

        log.info("Found {} total device(s) in domain '{}'", allDeviceIds.size, domain)
        return allDeviceIds
    }
}
