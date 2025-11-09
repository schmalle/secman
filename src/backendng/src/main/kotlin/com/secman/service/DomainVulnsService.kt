package com.secman.service

import com.secman.dto.DeviceVulnCountDto
import com.secman.dto.DomainGroupDto
import com.secman.dto.DomainVulnsSummaryDto
import com.secman.repository.AssetRepository
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
    private val vulnerabilityRepository: VulnerabilityRepository
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
        val allAssets = assetRepository.findAll().toList()
        val assetsInDomains = allAssets.filter { asset ->
            asset.adDomain != null && domains.any { domain ->
                domain.equals(asset.adDomain, ignoreCase = true)
            }
        }

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

        // Get vulnerabilities for all assets in user's domains
        val assetIds = assetsInDomains.mapNotNull { it.id }
        val allVulnerabilities = vulnerabilityRepository.findAll().toList()
            .filter { vuln -> assetIds.contains(vuln.asset.id) }

        log.info("Found {} vulnerabilities for assets in user's domains", allVulnerabilities.size)

        // Group vulnerabilities by asset (hostname)
        val vulnsByAsset = allVulnerabilities.groupBy { it.asset }

        // Create device vulnerability counts
        val deviceVulnCounts = assetsInDomains.map { asset ->
            val vulns = vulnsByAsset[asset] ?: emptyList()
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

        return DomainVulnsSummaryDto(
            domainGroups = domainGroups,
            totalDevices = deviceVulnCounts.size,
            totalVulnerabilities = allVulnerabilities.size,
            globalCritical = totalCritical,
            globalHigh = totalHigh,
            globalMedium = totalMedium,
            globalLow = totalLow,
            queriedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
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
}
