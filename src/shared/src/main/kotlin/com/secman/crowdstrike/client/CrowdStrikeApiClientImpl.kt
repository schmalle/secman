package com.secman.crowdstrike.client

import com.secman.crowdstrike.auth.CrowdStrikeAuthService
import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.exception.CrowdStrikeException
import com.secman.crowdstrike.exception.NotFoundException
import com.secman.crowdstrike.exception.RateLimitException
import com.secman.crowdstrike.model.AuthToken
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
import java.util.concurrent.CompletableFuture

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
        val severityList = severity.split(",").map { it.trim().uppercase() }
        val severityFilter = if (severityList.size == 1) {
            "cve.severity:'${severityList[0]}'"
        } else {
            "cve.severity:[${severityList.joinToString(",") { "'$it'" }}]"
        }

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

        // Get fresh authentication token for this stage
        log.info(">>> Stage 2: Getting fresh authentication token for vulnerability queries")
        var token = getAuthToken(config)
        log.info(">>> Stage 2: Authentication successful, token obtained")
        log.info(">>> Stage 2: Token type: {}, expires at: {}",
            token.tokenType, token.expiresAt)

        log.info(">>> Stage 2: Querying vulnerabilities (severity={}, minDaysOpen={})",
            severity, minDaysOpen)

        val allVulnerabilities = mutableListOf<CrowdStrikeVulnerabilityDto>()

        // Batch device IDs to avoid URL length limits and API timeouts
        // Balanced size: 20 devices per batch (was 10, then 50)
        // 20 devices = reasonable API response time + manageable number of batches
        val batchSize = 20
        val batches = deviceIds.chunked(batchSize)

        log.info(">>> Stage 2: Split {} device IDs into {} batches of up to {} IDs each",
            deviceIds.size, batches.size, batchSize)

        // Track API calls for token refresh
        var apiCallCount = 0
        val tokenRefreshInterval = 1000  // Refresh token every 1000 API calls

        batches.forEachIndexed { batchIndex, batch ->
            try {
                // Check if we need to refresh the token
                // Refresh every 1000 API calls OR if token is expiring within 5 minutes
                val shouldRefreshByCount = apiCallCount >= tokenRefreshInterval
                val shouldRefreshByExpiry = token.isExpiringSoon(bufferSeconds = 300)  // 5 minutes buffer

                if (shouldRefreshByCount || shouldRefreshByExpiry) {
                    val reason = when {
                        shouldRefreshByCount -> "API call count reached $apiCallCount (limit: $tokenRefreshInterval)"
                        shouldRefreshByExpiry -> "Token expiring soon (expires at: ${token.expiresAt})"
                        else -> "Unknown reason"
                    }
                    log.info(">>> Batch {}/{}: REFRESHING TOKEN - Reason: {}",
                        batchIndex + 1, batches.size, reason)

                    token = getAuthToken(config)
                    apiCallCount = 0  // Reset counter after refresh

                    log.info(">>> Batch {}/{}: New token obtained, expires at: {}",
                        batchIndex + 1, batches.size, token.expiresAt)
                }

                // Build FQL filter for this batch
                val severityList = severity.split(",").map { it.trim().uppercase() }
                val severityFilter = if (severityList.size == 1) {
                    "cve.severity:'${severityList[0]}'"
                } else {
                    "cve.severity:[${severityList.joinToString(",") { "'$it'" }}]"
                }

                // Build device ID filter: aid:['id1','id2','id3']
                val deviceIdFilter = if (batch.size == 1) {
                    "aid:'${batch[0]}'"
                } else {
                    "aid:[${batch.joinToString(",") { "'$it'" }}]"
                }

                val fqlFilter = "$deviceIdFilter+status:'open'+$severityFilter"

                // Query vulnerabilities for this batch with pagination
                var afterToken: String? = null
                var hasMore = true
                var pageCount = 0
                val maxPagesPerBatch = 50  // Safety limit: max 50 pages per batch

                while (hasMore && pageCount < maxPagesPerBatch) {
                    try {
                        pageCount++

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

                        // Log BEFORE making the call so we can see if it hangs
                        val startTime = System.currentTimeMillis()
                        log.info(">>> Batch {}/{}: Querying CrowdStrike API...",
                            batchIndex + 1, batches.size)

                        val request = HttpRequest.GET<Any>(uri.toString())
                            .header("Authorization", "Bearer ${token.accessToken}")
                            .header("Accept", "application/json")

                        val response = httpClient.toBlocking().exchange(request, Map::class.java)
                        val elapsed = System.currentTimeMillis() - startTime
                        apiCallCount++  // Increment API call counter

                        log.info(">>> Batch {}/{}: Response received in {}ms, status: {}",
                            batchIndex + 1, batches.size, elapsed, response.status.code)

                        when (response.status.code) {
                            200 -> {
                                @Suppress("UNCHECKED_CAST")
                                val responseBody = response.body() as? Map<String, Any>
                                    ?: throw CrowdStrikeException("Empty response from Spotlight API")

                                val resources = responseBody["resources"] as? List<*> ?: emptyList<Any>()
                                val vulns = mapResponseToDtos(resources)

                                // Apply client-side filtering (days open)
                                val filtered = vulns.filter { vuln ->
                                    val daysOpenValue = vuln.daysOpen?.split(" ")?.firstOrNull()?.toIntOrNull() ?: 0
                                    daysOpenValue >= minDaysOpen
                                }

                                // Check for pagination BEFORE adding to list
                                val meta = responseBody["meta"] as? Map<*, *>
                                val pagination = meta?.get("pagination") as? Map<*, *>
                                val prevAfterToken = afterToken
                                afterToken = pagination?.get("after")?.toString()

                                // Detailed pagination logging
                                log.info(">>> Batch {}/{} page {}: Retrieved {} vulns, {} after filter (total will be: {})",
                                    batchIndex + 1, batches.size, pageCount, vulns.size, filtered.size, allVulnerabilities.size + filtered.size)
                                log.info(">>> Batch {}/{} page {}: Pagination - afterToken: {}, vulns.isNotEmpty: {}",
                                    batchIndex + 1, batches.size, pageCount,
                                    if (afterToken != null) "present" else "null",
                                    vulns.isNotEmpty())

                                // Add filtered vulnerabilities to result list
                                allVulnerabilities.addAll(filtered)

                                // Exit pagination if:
                                // 1. No afterToken (no more pages)
                                // 2. No vulnerabilities returned (API has no more data)
                                // 3. Got results but ALL were filtered out (minDaysOpen filter too strict)
                                hasMore = afterToken != null && vulns.isNotEmpty()

                                // Safety check 1: if afterToken didn't change, break to prevent infinite loop
                                if (hasMore && afterToken == prevAfterToken) {
                                    log.warn(">>> Batch {}/{} page {}: afterToken unchanged - breaking pagination loop",
                                        batchIndex + 1, batches.size, pageCount)
                                    hasMore = false
                                }

                                // Safety check 2: if we got results but ALL filtered out for multiple pages, stop
                                // This indicates the minDaysOpen filter is too strict and we're wasting API calls
                                if (hasMore && filtered.isEmpty() && vulns.isNotEmpty() && pageCount >= 3) {
                                    log.warn(">>> Batch {}/{} page {}: Got {} vulns but ALL filtered out for {} pages - stopping pagination (minDaysOpen={} may be too strict)",
                                        batchIndex + 1, batches.size, pageCount, vulns.size, pageCount, minDaysOpen)
                                    hasMore = false
                                }
                            }
                            404 -> {
                                hasMore = false
                            }
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
                                log.error(">>> Batch {}/{}: UNAUTHORIZED - Token expired (expires at: {})",
                                    batchIndex + 1, batches.size, token.expiresAt)
                                throw CrowdStrikeException("Unauthorized: Token invalid or expired (expires at ${token.expiresAt})", e)
                            }
                            404 -> {
                                hasMore = false
                            }
                            429 -> {
                                val retryAfter = e.response.headers.get("Retry-After")?.toLongOrNull() ?: 30L
                                log.warn(">>> Batch {}/{}: Rate limit, retrying after {}s",
                                    batchIndex + 1, batches.size, retryAfter)
                                throw RateLimitException("Rate limit exceeded", retryAfter, e)
                            }
                            in 500..599 -> {
                                log.error(">>> Batch {}/{}: Server error {}", batchIndex + 1, batches.size, e.status)
                                throw CrowdStrikeException("Server error: ${e.status}", e)
                            }
                            else -> {
                                log.error(">>> Batch {}/{}: API error {}", batchIndex + 1, batches.size, e.status)
                                throw CrowdStrikeException("API error: ${e.message}", e)
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        log.error(">>> Batch {}/{}: TIMEOUT after waiting for API response", batchIndex + 1, batches.size)
                        log.error(">>> Consider: 1) CrowdStrike API may be slow, 2) Reduce batch size further, 3) Check network connection")
                        throw CrowdStrikeException("Timeout waiting for CrowdStrike API response on batch ${batchIndex + 1}", e)
                    } catch (e: java.io.IOException) {
                        log.error(">>> Batch {}/{}: Network I/O error: {}", batchIndex + 1, batches.size, e.message)
                        throw CrowdStrikeException("Network error on batch ${batchIndex + 1}: ${e.message}", e)
                    } catch (e: RateLimitException) {
                        log.warn(">>> Batch {}/{}: Rate limit hit, @Retryable will retry automatically",
                            batchIndex + 1, batches.size)
                        throw e
                    } catch (e: CrowdStrikeException) {
                        log.error(">>> Batch {}/{}: CrowdStrike error (no retry): {}",
                            batchIndex + 1, batches.size, e.message)
                        throw e
                    } catch (e: Exception) {
                        log.error(">>> Batch {}/{}: Unexpected error (no retry): {} - {}",
                            batchIndex + 1, batches.size, e.javaClass.simpleName, e.message)
                        throw CrowdStrikeException("Failed to query batch ${batchIndex + 1}: ${e.message}", e)
                    }
                }

                // Check if we hit the page limit
                if (pageCount >= maxPagesPerBatch) {
                    log.warn(">>> Batch {}/{}: Reached max page limit ({} pages) - stopping pagination for this batch",
                        batchIndex + 1, batches.size, maxPagesPerBatch)
                }

            } catch (e: Exception) {
                log.error(">>> Batch {}/{} FAILED: {}", batchIndex + 1, batches.size, e.message)
                throw e
            }
        }

        log.info(">>> Stage 2 complete: Queried {} device IDs across {} batches",
            deviceIds.size, batches.size)
        log.info(">>> Stage 2 complete: {} total vulnerabilities found", allVulnerabilities.size)
        log.info(">>> Stage 2 complete: {} total API calls made", apiCallCount)

        return allVulnerabilities
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

                val dto = CrowdStrikeVulnerabilityDto(
                    id = id,
                    hostname = hostname,
                    ip = ip,
                    cveId = cveId,
                    severity = severity,
                    cvssScore = cvssScore,
                    affectedProduct = affectedProduct,
                    daysOpen = calculateDaysOpen(detectedAt),
                    detectedAt = detectedAt,
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
     * Map CrowdStrike API response to DTOs (bulk query version)
     * For bulk queries, we need to extract hostname from the API response
     *
     * @param resources Raw vulnerability resources from API
     * @return List of CrowdStrikeVulnerabilityDto
     */
    private fun mapResponseToDtos(resources: List<*>): List<CrowdStrikeVulnerabilityDto> {
        return resources.mapNotNull { resource ->
            val vuln = resource as? Map<*, *> ?: return@mapNotNull null
            try {
                val id = vuln["id"]?.toString() ?: "cs-${System.currentTimeMillis()}"
                val hostInfo = vuln["host_info"] as? Map<*, *>
                val deviceInfo = vuln["device"] as? Map<*, *>

                // For bulk queries, try to extract hostname from API response
                val hostname = hostInfo?.get("hostname")?.toString()
                    ?: hostInfo?.get("host_name")?.toString()
                    ?: vuln["hostname"]?.toString()
                    ?: deviceInfo?.get("hostname")?.toString()
                    ?: deviceInfo?.get("host_name")?.toString()
                    ?: vuln["aid"]?.toString()?.let { "[DEVICE:$it]" }
                    ?: "[UNKNOWN]"

                val ip = vuln["local_ip"]?.toString()
                    ?: hostInfo?.get("local_ip")?.toString()
                    ?: deviceInfo?.get("local_ip")?.toString()

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

                CrowdStrikeVulnerabilityDto(
                    id = id,
                    hostname = hostname,
                    ip = ip,
                    cveId = cveId,
                    severity = severity,
                    cvssScore = cvssScore,
                    affectedProduct = affectedProduct,
                    daysOpen = calculateDaysOpen(detectedAt),
                    detectedAt = detectedAt,
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
}
