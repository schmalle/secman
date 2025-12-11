package com.secman.service

import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.dto.DeviceVulnCountDto
import com.secman.dto.DomainGroupDto
import com.secman.dto.DomainSyncResultDto
import com.secman.dto.DomainVulnsSummaryDto
import com.secman.dto.VulnerabilityDto
import com.secman.repository.AssetRepository
import com.secman.repository.FalconConfigRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Domain Vulnerabilities Service
 *
 * Feature: 043-crowdstrike-domain-import
 *
 * Provides domain-based vulnerability view for non-admin users.
 * Queries secman database for vulnerabilities based on user's domain mappings.
 *
 * Similar to AccountVulnsService but:
 * - Uses domain mappings instead of AWS account mappings
 * - Queries local database (not CrowdStrike Falcon API)
 * - Groups results by AD domain
 *
 * Access Control:
 * - Non-admin users only (admins should use System Vulns view)
 * - User must have domain mappings in UserMapping table
 * - Returns 404 if user has no domain mappings
 *
 * @see AccountVulnsService
 */
@Singleton
class DomainVulnsService(
    private val userMappingRepository: UserMappingRepository,
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val crowdStrikeApiClient: CrowdStrikeApiClient,
    private val falconConfigRepository: FalconConfigRepository,
    private val importService: CrowdStrikeVulnerabilityImportService
) {
    private val log = LoggerFactory.getLogger(DomainVulnsService::class.java)

    /**
     * Get domain-based vulnerabilities summary from secman database
     *
     * Workflow:
     * 1. Extract user email from authentication
     * 2. Verify user is NOT admin (admins use system vulns view)
     * 3. Get user's domain mappings
     * 4. Query database for assets with matching AD domains
     * 5. Query vulnerabilities for those assets
     * 6. Aggregate and group results by domain
     *
     * @param authentication User authentication context
     * @return Domain vulnerabilities summary
     * @throws IllegalStateException if user is admin
     * @throws IllegalArgumentException if user has no domain mappings
     */
    fun getDomainVulnsSummary(authentication: Authentication): DomainVulnsSummaryDto {
        // Extract user email
        val email = authentication.attributes["email"]?.toString()
            ?: throw IllegalArgumentException("User email not found in authentication context")

        log.info("Getting domain vulnerabilities from database for user: {}", email)

        // Check if user is admin
        if (authentication.roles.contains("ADMIN")) {
            throw IllegalStateException("Admin users should use System Vulnerabilities view instead")
        }

        // Get user's domain mappings
        val domains = userMappingRepository.findDistinctDomainByEmail(email)
        if (domains.isEmpty()) {
            throw IllegalArgumentException("User has no domain mappings. Please contact administrator.")
        }

        log.info("User {} has {} domain mapping(s): {}", email, domains.size, domains.joinToString(", "))

        // Query database for assets matching user's domains (case-insensitive)
        // Feature 053: Use database-level filtering instead of loading all assets
        val assetsInDomains = assetRepository.findByAdDomainInIgnoreCase(domains)

        log.info("Found {} assets in user's domains", assetsInDomains.size)

        if (assetsInDomains.isEmpty()) {
            // No assets found in user's domains - return empty summary
            return DomainVulnsSummaryDto(
                domainGroups = domains.map { domain ->
                    DomainGroupDto(
                        domain = domain,
                        devices = emptyList(),
                        totalDevices = 0,
                        totalVulnerabilities = 0,
                        totalCritical = 0,
                        totalHigh = 0,
                        totalMedium = 0,
                        totalLow = 0
                    )
                },
                totalDevices = 0,
                totalVulnerabilities = 0,
                globalCritical = 0,
                globalHigh = 0,
                globalMedium = 0,
                globalLow = 0,
                queriedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
        }

        // Get vulnerabilities for all assets in user's domains (only from latest import per asset)
        val assetIds = assetsInDomains.mapNotNull { it.id }
        val allVulnerabilities = if (assetIds.isNotEmpty()) {
            vulnerabilityRepository.findLatestVulnerabilitiesForAssetIds(assetIds.toSet())
        } else {
            emptyList()
        }

        log.info("Found {} vulnerabilities for assets in user's domains", allVulnerabilities.size)

        // Group vulnerabilities by asset ID (more reliable than object reference)
        // Native queries may return entities with different object references
        val vulnsByAssetId = allVulnerabilities.groupBy { it.asset.id }

        // Create device vulnerability counts using asset ID for lookup
        val deviceVulnCounts = assetsInDomains.map { asset ->
            val vulns = vulnsByAssetId[asset.id] ?: emptyList()
            DeviceVulnCountDto(
                hostname = asset.name,
                ip = asset.ip,
                vulnerabilityCount = vulns.size,
                criticalCount = vulns.count { it.cvssSeverity.equals("Critical", ignoreCase = true) },
                highCount = vulns.count { it.cvssSeverity.equals("High", ignoreCase = true) },
                mediumCount = vulns.count { it.cvssSeverity.equals("Medium", ignoreCase = true) },
                lowCount = vulns.count { it.cvssSeverity.equals("Low", ignoreCase = true) }
            )
        }.sortedByDescending { it.vulnerabilityCount }

        // Group devices by domain
        val domainGroups = createDomainGroupsFromDatabase(assetsInDomains, deviceVulnCounts, allVulnerabilities)

        // Calculate global totals
        val totalCritical = deviceVulnCounts.sumOf { it.criticalCount ?: 0 }
        val totalHigh = deviceVulnCounts.sumOf { it.highCount ?: 0 }
        val totalMedium = deviceVulnCounts.sumOf { it.mediumCount ?: 0 }
        val totalLow = deviceVulnCounts.sumOf { it.lowCount ?: 0 }

        // Get latest import timestamp for "Last Synced" indicator
        val lastSyncedAt = if (assetIds.isNotEmpty()) {
            vulnerabilityRepository.findLatestImportTimestampByAssetIds(assetIds.toSet())
                ?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } else null

        return DomainVulnsSummaryDto(
            domainGroups = domainGroups,
            totalDevices = deviceVulnCounts.size,
            totalVulnerabilities = allVulnerabilities.size,
            globalCritical = totalCritical,
            globalHigh = totalHigh,
            globalMedium = totalMedium,
            globalLow = totalLow,
            queriedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            lastSyncedAt = lastSyncedAt
        )
    }

    /**
     * Create domain groups from database assets
     *
     * Groups devices by their AD domain field.
     * Each domain gets its own group with its devices and vulnerability counts.
     */
    private fun createDomainGroupsFromDatabase(
        assets: List<com.secman.domain.Asset>,
        deviceVulnCounts: List<DeviceVulnCountDto>,
        allVulnerabilities: List<com.secman.domain.Vulnerability>
    ): List<DomainGroupDto> {
        // Create a map of asset name to device count for quick lookup
        val deviceCountMap = deviceVulnCounts.associateBy { it.hostname }

        // Group assets by their AD domain (case-insensitive)
        val assetsByDomain = assets.groupBy { it.adDomain?.uppercase() ?: "UNKNOWN" }

        // Create domain groups
        return assetsByDomain.map { (normalizedDomain, domainAssets) ->
            // Get the original casing from the first asset
            val displayDomain = domainAssets.firstOrNull()?.adDomain ?: normalizedDomain

            // Get devices for this domain
            val devicesInDomain = domainAssets.mapNotNull { asset ->
                deviceCountMap[asset.name]
            }

            // Calculate domain-level totals
            val totalCritical = devicesInDomain.sumOf { it.criticalCount ?: 0 }
            val totalHigh = devicesInDomain.sumOf { it.highCount ?: 0 }
            val totalMedium = devicesInDomain.sumOf { it.mediumCount ?: 0 }
            val totalLow = devicesInDomain.sumOf { it.lowCount ?: 0 }

            DomainGroupDto(
                domain = displayDomain,
                devices = devicesInDomain.sortedByDescending { it.vulnerabilityCount },
                totalDevices = devicesInDomain.size,
                totalVulnerabilities = devicesInDomain.sumOf { it.vulnerabilityCount },
                totalCritical = totalCritical,
                totalHigh = totalHigh,
                totalMedium = totalMedium,
                totalLow = totalLow
            )
        }.sortedByDescending { it.totalVulnerabilities }
    }

    /**
     * Get user's domain mappings
     *
     * @param email User email address
     * @return List of domain names the user has access to
     */
    fun getUserDomains(email: String): List<String> {
        return userMappingRepository.findDistinctDomainByEmail(email)
    }

    /**
     * Sync domain vulnerabilities from CrowdStrike Falcon API to secman database
     *
     * Feature: Domain Vulnerability Sync
     *
     * Workflow:
     * 1. Query CrowdStrike Falcon API for all devices in the specified domain
     * 2. Convert vulnerabilities to batch format
     * 3. Import using transactional replace pattern (delete old + insert new)
     * 4. Return sync statistics
     *
     * @param domain AD domain name to sync (e.g., "CONTOSO")
     * @param triggeredBy User that triggered the sync (for audit trail)
     * @return DomainSyncResultDto with sync statistics
     * @throws IllegalStateException if CrowdStrike API is not configured
     */
    fun syncDomainFromCrowdStrike(domain: String, triggeredBy: String): DomainSyncResultDto {
        log.info("Starting domain sync from CrowdStrike: domain={}, triggeredBy={}", domain, triggeredBy)

        // Get CrowdStrike configuration
        val config = getConfiguration()

        // Query CrowdStrike for all devices in the domain
        // Use empty severity filter to get all vulnerabilities
        val response = crowdStrikeApiClient.queryVulnerabilitiesByDomains(
            domains = listOf(domain),
            severity = "",  // No severity filter - get all
            minDaysOpen = 0,
            config = config,
            limit = 10000  // High limit to get all vulnerabilities
        )

        log.info("CrowdStrike query returned {} vulnerabilities for domain {}",
            response.vulnerabilities.size, domain)

        if (response.vulnerabilities.isEmpty()) {
            log.warn("No vulnerabilities found in CrowdStrike for domain {}", domain)
            return DomainSyncResultDto(
                domain = domain,
                syncedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                devicesProcessed = 0,
                devicesCreated = 0,
                devicesUpdated = 0,
                vulnerabilitiesImported = 0
            )
        }

        // Group vulnerabilities by hostname to create batch DTOs
        val batches = groupVulnerabilitiesByHostname(response.vulnerabilities, domain)

        log.info("Grouped vulnerabilities into {} device batches for domain {}", batches.size, domain)

        // Import using the existing import service
        val stats = importService.importServerVulnerabilities(batches, triggeredBy, triggerRefresh = true)

        log.info("Domain sync completed: domain={}, devices={}, created={}, updated={}, vulns={}",
            domain, stats.serversProcessed, stats.serversCreated, stats.serversUpdated, stats.vulnerabilitiesImported)

        return DomainSyncResultDto(
            domain = domain,
            syncedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            devicesProcessed = stats.serversProcessed,
            devicesCreated = stats.serversCreated,
            devicesUpdated = stats.serversUpdated,
            vulnerabilitiesImported = stats.vulnerabilitiesImported
        )
    }

    /**
     * Get CrowdStrike Falcon configuration from database
     *
     * @return FalconConfigDto with API credentials
     * @throws IllegalStateException if configuration not found
     */
    private fun getConfiguration(): FalconConfigDto {
        val configOpt = falconConfigRepository.findActiveConfig()
        val config = if (configOpt.isPresent) {
            configOpt.get()
        } else {
            throw IllegalStateException("No active CrowdStrike configuration found. Contact administrator.")
        }

        // Map cloud region to base URL
        val baseUrl = when (config.cloudRegion) {
            "us-1" -> "https://api.crowdstrike.com"
            "us-2" -> "https://api.us-2.crowdstrike.com"
            "eu-1" -> "https://api.eu-1.crowdstrike.com"
            "us-gov-1" -> "https://api.us-gov-1.crowdstrike.com"
            "us-gov-2" -> "https://api.us-gov-2.crowdstrike.com"
            else -> "https://api.crowdstrike.com"
        }

        return FalconConfigDto(
            clientId = config.clientId,
            clientSecret = config.clientSecret,
            baseUrl = baseUrl
        )
    }

    /**
     * Group CrowdStrike vulnerabilities by hostname into batch DTOs
     *
     * @param vulnerabilities List of vulnerabilities from CrowdStrike
     * @param domain The AD domain for metadata
     * @return List of batch DTOs ready for import
     */
    private fun groupVulnerabilitiesByHostname(
        vulnerabilities: List<com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto>,
        domain: String
    ): List<CrowdStrikeVulnerabilityBatchDto> {
        // Group by hostname
        val byHostname = vulnerabilities.groupBy { it.hostname }

        return byHostname.map { (hostname, vulns) ->
            val firstVuln = vulns.first()

            CrowdStrikeVulnerabilityBatchDto(
                hostname = hostname,
                ip = firstVuln.ip,
                groups = null,  // Not available from API
                cloudAccountId = null,  // Not in this query
                cloudInstanceId = null,  // Not in this query
                adDomain = domain,
                osVersion = null,  // Not in this query
                vulnerabilities = vulns.map { vuln ->
                    // Parse daysOpen from string (e.g., "15 days" -> 15)
                    val daysOpenInt = vuln.daysOpen?.split(" ")?.firstOrNull()?.toIntOrNull() ?: 0

                    VulnerabilityDto(
                        cveId = vuln.cveId ?: "",
                        severity = vuln.severity,
                        affectedProduct = vuln.affectedProduct,
                        daysOpen = daysOpenInt,
                        patchPublicationDate = vuln.patchPublicationDate
                    )
                }
            )
        }
    }
}
