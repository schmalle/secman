package com.secman.service

import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.exception.CrowdStrikeException
import com.secman.crowdstrike.exception.NotFoundException
import com.secman.crowdstrike.exception.RateLimitException
import com.secman.domain.FalconConfig
import com.secman.dto.CrowdStrikeQueryResponse
import com.secman.repository.FalconConfigRepository
import io.micronaut.cache.annotation.Cacheable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Query type enum for cache key discrimination
 *
 * Feature: 041-falcon-instance-lookup
 * Task: T004
 *
 * Enables different cache keys for hostname vs AWS instance ID queries
 */
enum class QueryType {
    HOSTNAME,
    INSTANCE_ID
}

/**
 * Service for querying CrowdStrike using the shared API client
 *
 * Provides:
 * - Integration with shared CrowdStrikeApiClient
 * - Caching of vulnerability results (15 minute TTL)
 * - Error mapping to internal exceptions
 * - Configuration retrieval from database
 *
 * Related to: Feature 023-create-in-the (Phase 5: Backend API Integration)
 * Task: T062-T065
 */
@Singleton
open class CrowdStrikeQueryService(
    private val apiClient: CrowdStrikeApiClient,
    private val falconConfigRepository: FalconConfigRepository
) {
    private val log = LoggerFactory.getLogger(CrowdStrikeQueryService::class.java)

    /**
     * Query vulnerabilities with caching
     *
     * Results are cached for 15 minutes per hostname+severity+product combination
     *
     * Task: T062-T065
     *
     * @param hostname System hostname
     * @param severity Optional severity filter
     * @param product Optional product filter
     * @param limit Page size (default: 100)
     * @return CrowdStrikeQueryResponse
     * @throws CrowdStrikeError on API errors
     */
    @Cacheable("vulnerability_queries")
    open fun queryVulnerabilities(
        hostname: String,
        severity: String? = null,
        product: String? = null,
        limit: Int = 100
    ): CrowdStrikeQueryResponse {
        require(hostname.isNotBlank()) { "Hostname cannot be blank" }

        log.info(
            "Querying vulnerabilities: hostname={}, severity={}, product={}, limit={}",
            hostname, severity, product, limit
        )

        try {
            // Get config from database
            val config = getConfiguration()

            // Query API (returns shared module's CrowdStrikeQueryResponse)
            val sharedResponse = apiClient.queryAllVulnerabilities(hostname, config, limit)

            // Convert shared response to backend response, then apply filters
            val backendResponse = sharedResponse.toBackendResponse()
            val filtered = applyFilters(backendResponse, severity, product)

            log.info(
                "Query successful: hostname={}, found={}, filtered={}",
                hostname, sharedResponse.vulnerabilities.size, filtered.vulnerabilities.size
            )

            return filtered
        } catch (e: NotFoundException) {
            log.warn("Hostname not found: {}", hostname)
            throw CrowdStrikeError.NotFoundError(
                hostname = hostname,
                cause = e
            )
        } catch (e: RateLimitException) {
            log.warn("Rate limit exceeded: {}", e.message)
            throw CrowdStrikeError.RateLimitError(
                retryAfterSeconds = 60,
                cause = e
            )
        } catch (e: CrowdStrikeException) {
            log.error("CrowdStrike API error: {}", e.message, e)
            throw CrowdStrikeError.ServerError(
                message = "CrowdStrike API error: ${e.message}",
                cause = e
            )
        } catch (e: CrowdStrikeError) {
            // Re-throw internal errors
            throw e
        } catch (e: Exception) {
            log.error("Unexpected error querying CrowdStrike", e)
            throw CrowdStrikeError.ServerError(
                message = "Unexpected error: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Get CrowdStrike configuration from database
     *
     * @return FalconConfigDto with API credentials
     * @throws CrowdStrikeError.ConfigurationError if config not found
     */
    private fun getConfiguration(): FalconConfigDto {
        return try {
            val configOpt = falconConfigRepository.findActiveConfig()
            val config = if (configOpt.isPresent) {
                configOpt.get()
            } else {
                throw IllegalStateException("No active CrowdStrike configuration found")
            }

            // Map domain model to DTO with proper base URL
            val baseUrl = mapCloudRegionToBaseUrl(config.cloudRegion)

            FalconConfigDto(
                clientId = config.clientId,
                clientSecret = config.clientSecret,
                baseUrl = baseUrl
            )
        } catch (e: CrowdStrikeError) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to load CrowdStrike configuration", e)
            throw CrowdStrikeError.ConfigurationError(
                message = "CrowdStrike configuration not configured. Contact administrator.",
                cause = e
            )
        }
    }

    /**
     * Map CrowdStrike cloud region to API base URL
     *
     * @param cloudRegion Cloud region code (us-1, eu-1, etc.)
     * @return API base URL
     */
    private fun mapCloudRegionToBaseUrl(cloudRegion: String): String {
        return when (cloudRegion) {
            "us-1" -> "https://api.crowdstrike.com"
            "us-2" -> "https://api.us-2.crowdstrike.com"
            "eu-1" -> "https://api.eu-1.crowdstrike.com"
            "us-gov-1" -> "https://api.us-gov-1.crowdstrike.com"
            "us-gov-2" -> "https://api.us-gov-2.crowdstrike.com"
            else -> "https://api.crowdstrike.com" // Default to us-1
        }
    }

    /**
     * Query vulnerabilities by AWS EC2 Instance ID with caching
     *
     * Feature: 041-falcon-instance-lookup
     * Task: T014
     *
     * Results are cached for 15 minutes per instanceId+severity+product combination
     *
     * @param instanceId AWS EC2 Instance ID (format: i-XXXXXXXXX...)
     * @param severity Optional severity filter
     * @param product Optional product filter
     * @param limit Page size (default: 100)
     * @return CrowdStrikeQueryResponse
     * @throws CrowdStrikeError on API errors
     */
    @Cacheable("vulnerability_queries")
    open fun queryByInstanceId(
        instanceId: String,
        severity: String? = null,
        product: String? = null,
        limit: Int = 100
    ): CrowdStrikeQueryResponse {
        require(instanceId.isNotBlank()) { "Instance ID cannot be blank" }

        log.info(
            "Querying vulnerabilities by instance ID: instanceId={}, severity={}, product={}, limit={}",
            instanceId, severity, product, limit
        )

        try {
            // Get config from database
            val config = getConfiguration()

            // Query API by instance ID (returns shared module's CrowdStrikeQueryResponse)
            val sharedResponse = apiClient.queryVulnerabilitiesByInstanceId(instanceId, config)

            // Convert shared response to backend response, then apply filters
            val backendResponse = sharedResponse.toBackendResponse()
            val filtered = applyFilters(backendResponse, severity, product)

            log.info(
                "Query successful: instanceId={}, devices={}, found={}, filtered={}",
                instanceId, sharedResponse.deviceCount, sharedResponse.vulnerabilities.size, filtered.vulnerabilities.size
            )

            return filtered
        } catch (e: NotFoundException) {
            log.warn("Instance ID not found: {}", instanceId)
            throw CrowdStrikeError.NotFoundError(
                hostname = instanceId,
                cause = e
            )
        } catch (e: RateLimitException) {
            log.warn("Rate limit exceeded: {}", e.message)
            throw CrowdStrikeError.RateLimitError(
                retryAfterSeconds = 60,
                cause = e
            )
        } catch (e: CrowdStrikeException) {
            log.error("CrowdStrike API error: {}", e.message, e)
            throw CrowdStrikeError.ServerError(
                message = "CrowdStrike API error: ${e.message}",
                cause = e
            )
        } catch (e: CrowdStrikeError) {
            // Re-throw internal errors
            throw e
        } catch (e: Exception) {
            log.error("Unexpected error querying CrowdStrike by instance ID", e)
            throw CrowdStrikeError.ServerError(
                message = "Unexpected error: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Apply filters to vulnerability results
     *
     * @param response Original response from API
     * @param severity Optional severity filter (case-insensitive)
     * @param product Optional product filter (substring match)
     * @return Filtered response
     */
    private fun applyFilters(
        response: CrowdStrikeQueryResponse,
        severity: String?,
        product: String?
    ): CrowdStrikeQueryResponse {
        var filtered = response.vulnerabilities

        // Filter by severity
        if (!severity.isNullOrBlank()) {
            filtered = filtered.filter {
                it.severity.lowercase() == severity.lowercase()
            }
        }

        // Filter by product
        if (!product.isNullOrBlank()) {
            filtered = filtered.filter {
                it.affectedProduct?.contains(product, ignoreCase = true) == true
            }
        }

        return response.copy(
            vulnerabilities = filtered,
            totalCount = filtered.size,
            queriedAt = LocalDateTime.now()
        )
    }
}

/**
 * Extension function to convert shared module's CrowdStrikeQueryResponse to backend DTO
 *
 * Feature 041: Now includes instanceId and deviceCount fields
 */
private fun com.secman.crowdstrike.dto.CrowdStrikeQueryResponse.toBackendResponse(): CrowdStrikeQueryResponse {
    return CrowdStrikeQueryResponse(
        hostname = this.hostname,
        instanceId = this.instanceId,
        deviceCount = this.deviceCount,
        vulnerabilities = this.vulnerabilities.map { shared ->
            com.secman.dto.CrowdStrikeVulnerabilityDto(
                id = shared.id,
                hostname = shared.hostname,
                ip = shared.ip,
                cveId = shared.cveId,
                severity = shared.severity,
                cvssScore = shared.cvssScore,
                affectedProduct = shared.affectedProduct,
                daysOpen = shared.daysOpen,
                detectedAt = shared.detectedAt,
                status = shared.status,
                hasException = shared.hasException,
                exceptionReason = shared.exceptionReason
            )
        },
        totalCount = this.totalCount,
        queriedAt = this.queriedAt
    )
}
