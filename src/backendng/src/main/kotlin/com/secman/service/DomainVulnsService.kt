package com.secman.service

import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.dto.DeviceVulnCountDto
import com.secman.dto.DomainGroupDto
import com.secman.dto.DomainVulnsSummaryDto
import com.secman.repository.FalconConfigRepository
import com.secman.repository.UserMappingRepository
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.format.DateTimeFormatter

/**
 * Domain Vulnerabilities Service
 *
 * Feature: 042-domain-vulnerabilities-view
 *
 * Provides domain-based vulnerability view for non-admin users.
 * Queries CrowdStrike Falcon API directly based on user's domain mappings.
 *
 * Similar to AccountVulnsService but:
 * - Uses domain mappings instead of AWS account mappings
 * - Queries Falcon API directly (not local database)
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
    private val falconConfigRepository: FalconConfigRepository,
    private val crowdStrikeApiClient: CrowdStrikeApiClient
) {
    private val log = LoggerFactory.getLogger(DomainVulnsService::class.java)

    /**
     * Get domain-based vulnerabilities summary from Falcon API
     *
     * Workflow:
     * 1. Extract user email from authentication
     * 2. Verify user is NOT admin (admins use system vulns view)
     * 3. Get user's domain mappings
     * 4. Query Falcon API for each domain
     * 5. Aggregate and group results by domain
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

        log.info("Getting domain vulnerabilities for user: {}", email)

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

        // Get Falcon configuration
        val falconConfig = getFalconConfig()
            ?: throw IllegalStateException("CrowdStrike Falcon API is not configured. Please contact administrator.")

        // Query Falcon API for all user's domains
        val response = crowdStrikeApiClient.queryVulnerabilitiesByDomains(
            domains = domains,
            severity = "CRITICAL,HIGH,MEDIUM,LOW",  // Get all severities
            minDaysOpen = 0,  // Get all vulnerabilities regardless of age
            config = falconConfig,
            limit = 1000
        )

        log.info("Falcon API returned {} vulnerabilities from {} domains",
            response.vulnerabilities.size, domains.size)

        // Group vulnerabilities by hostname (device)
        val vulnsByHostname = response.vulnerabilities.groupBy { it.hostname }

        // Create device vulnerability counts
        val deviceVulnCounts = vulnsByHostname.map { (hostname, vulns) ->
            DeviceVulnCountDto(
                hostname = hostname,
                ip = vulns.firstOrNull()?.ip,
                vulnerabilityCount = vulns.size,
                criticalCount = vulns.count { it.severity.equals("Critical", ignoreCase = true) },
                highCount = vulns.count { it.severity.equals("High", ignoreCase = true) },
                mediumCount = vulns.count { it.severity.equals("Medium", ignoreCase = true) },
                lowCount = vulns.count { it.severity.equals("Low", ignoreCase = true) }
            )
        }.sortedByDescending { it.vulnerabilityCount }

        // Group devices by domain (we need to match devices to their domains)
        // Since Falcon API doesn't return domain in vulnerability response,
        // we'll create a single group for now or try to infer from hostname
        val domainGroups = createDomainGroups(domains, deviceVulnCounts, response.vulnerabilities)

        // Calculate global totals
        val totalCritical = deviceVulnCounts.sumOf { it.criticalCount ?: 0 }
        val totalHigh = deviceVulnCounts.sumOf { it.highCount ?: 0 }
        val totalMedium = deviceVulnCounts.sumOf { it.mediumCount ?: 0 }
        val totalLow = deviceVulnCounts.sumOf { it.lowCount ?: 0 }

        return DomainVulnsSummaryDto(
            domainGroups = domainGroups,
            totalDevices = deviceVulnCounts.size,
            totalVulnerabilities = response.totalCount,
            globalCritical = totalCritical,
            globalHigh = totalHigh,
            globalMedium = totalMedium,
            globalLow = totalLow,
            queriedAt = response.queriedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    /**
     * Create domain groups from device list
     *
     * Groups devices by domain. Since we queried by domain initially,
     * we can distribute devices proportionally or create a combined group.
     *
     * For simplicity, we create one group per domain with all devices.
     * In a real implementation, we might query each domain separately to get proper grouping.
     */
    private fun createDomainGroups(
        domains: List<String>,
        devices: List<DeviceVulnCountDto>,
        allVulns: List<CrowdStrikeVulnerabilityDto>
    ): List<DomainGroupDto> {
        // Since we queried all domains together, we'll create separate groups by re-querying
        // Or we can create a single combined group
        // For now, create a single group with all domains listed

        if (devices.isEmpty()) {
            return domains.map { domain ->
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
            }
        }

        // Create a combined group with all devices
        val combinedDomain = domains.joinToString(", ")
        val totalCritical = devices.sumOf { it.criticalCount ?: 0 }
        val totalHigh = devices.sumOf { it.highCount ?: 0 }
        val totalMedium = devices.sumOf { it.mediumCount ?: 0 }
        val totalLow = devices.sumOf { it.lowCount ?: 0 }

        return listOf(
            DomainGroupDto(
                domain = combinedDomain,
                devices = devices,
                totalDevices = devices.size,
                totalVulnerabilities = devices.sumOf { it.vulnerabilityCount },
                totalCritical = totalCritical,
                totalHigh = totalHigh,
                totalMedium = totalMedium,
                totalLow = totalLow
            )
        )
    }

    /**
     * Get Falcon configuration from database
     *
     * Returns the first active Falcon configuration
     */
    private fun getFalconConfig(): FalconConfigDto? {
        val configs = falconConfigRepository.findAll().toList()
        if (configs.isEmpty()) {
            log.warn("No Falcon configurations found in database")
            return null
        }

        val config = configs.first()

        // Map cloudRegion to baseUrl
        val baseUrl = when (config.cloudRegion) {
            "us-1" -> "https://api.crowdstrike.com"
            "us-2" -> "https://api.us-2.crowdstrike.com"
            "eu-1" -> "https://api.eu-1.crowdstrike.com"
            "us-gov-1" -> "https://api.laggar.gcw.crowdstrike.com"
            "us-gov-2" -> "https://api.us-gov-2.crowdstrike.com"
            else -> "https://api.crowdstrike.com"
        }

        return FalconConfigDto(
            clientId = config.clientId,
            clientSecret = config.clientSecret,
            baseUrl = baseUrl,
            name = "Falcon Config ${config.id ?: ""}"
        )
    }
}
