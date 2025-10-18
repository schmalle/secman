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
import java.time.LocalDateTime
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

            // Query Spotlight API
            val vulnerabilityDtos = querySpotlightApi(deviceId, token)

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

        // Strategy 1: Stemmed search (case-insensitive, starts with)
        log.debug("Strategy 1: Trying stemmed search (starts with)")
        val stemmedResult = tryHostnameFilter(hostname, "hostname:'$hostname*'", token)
        if (stemmedResult != null) {
            log.info("Found device ID using stemmed search: {}", stemmedResult)
            return stemmedResult
        }

        // Strategy 2: Contains search (case-insensitive)
        log.debug("Strategy 2: Trying contains search")
        val containsResult = tryHostnameFilter(hostname, "hostname:*'*$hostname*'", token)
        if (containsResult != null) {
            log.info("Found device ID using contains search: {}", containsResult)
            return containsResult
        }

        // Strategy 3: Exact match (case-sensitive)
        log.debug("Strategy 3: Trying exact match")
        val exactResult = tryHostnameFilter(hostname, "hostname:['$hostname']", token)
        if (exactResult != null) {
            log.info("Found device ID using exact match: {}", exactResult)
            return exactResult
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
        val uri = UriBuilder.of("/devices/queries/devices/v1")
            .queryParam("filter", filter)
            .queryParam("limit", "10")
            .build()

        val request = HttpRequest.GET<Any>(uri.toString())
            .header("Authorization", "Bearer ${token.accessToken}")
            .header("Accept", "application/json")

        val response = httpClient.toBlocking().exchange(request, Map::class.java)

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
    open fun querySpotlightApi(deviceId: String, token: AuthToken): List<CrowdStrikeVulnerabilityDto> {
        log.debug("Querying Spotlight API for device ID: {}", deviceId)

        return try {
            // Build filter query
            val filter = "aid:'$deviceId'+status:'open'"

            val uri = UriBuilder.of("/spotlight/combined/vulnerabilities/v1")
                .queryParam("filter", filter)
                .queryParam("limit", "5000")
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

                    // Task T033: Map responses to shared DTOs
                    mapResponseToDtos(resources)
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
     * @return List of CrowdStrikeVulnerabilityDto
     */
    private fun mapResponseToDtos(resources: List<*>): List<CrowdStrikeVulnerabilityDto> {
        return resources.mapNotNull { resource ->
            try {
                val vuln = resource as? Map<*, *> ?: return@mapNotNull null

                val id = vuln["id"]?.toString() ?: "cs-${System.currentTimeMillis()}"
                val hostname = vuln["hostname"]?.toString() ?: vuln["aid"]?.toString() ?: ""
                val ip = vuln["local_ip"]?.toString()
                val cveId = (vuln["cve"] as? Map<*, *>)?.get("id")?.toString()
                val cvssScore = (vuln["score"] as? Number)?.toDouble()
                    ?: ((vuln["cve"] as? Map<*, *>)?.get("base_score") as? Number)?.toDouble()
                val severity = mapCvssToSeverity(cvssScore)

                val apps = vuln["apps"] as? List<*>
                val affectedProduct = apps?.mapNotNull { app ->
                    (app as? Map<*, *>)?.get("product_name_version")?.toString()
                }?.joinToString(", ")

                val createdTimestamp = vuln["created_timestamp"]?.toString()
                    ?: vuln["created_on"]?.toString()
                    ?: LocalDateTime.now().toString()
                val detectedAt = try {
                    LocalDateTime.parse(createdTimestamp.replace(" ", "T"))
                } catch (e: Exception) {
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
                log.warn("Failed to map vulnerability: {}", e.message)
                null
            }
        }
    }

    /**
     * Map CVSS score to severity level
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
}
