package com.secman.service

import com.secman.dto.AssetVulnCountDto
import com.secman.dto.CrowdStrikeImportStatusDto
import com.secman.dto.WorkgroupGroupDto
import com.secman.dto.WorkgroupVulnsSummaryDto
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.WorkgroupRepository
import com.secman.repository.CrowdStrikeImportHistoryRepository
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory

/**
 * Service for retrieving vulnerability summaries grouped by workgroup.
 *
 * Feature: 022-wg-vulns-handling - Workgroup-Based Vulnerability View
 *
 * Provides business logic for the WG Vulns view, including:
 * - Looking up user's workgroup memberships
 * - Filtering assets by workgroup IDs
 * - Counting vulnerabilities per asset
 * - Grouping and sorting results
 *
 * Access Control:
 * - Admin users are rejected (should use System Vulns instead)
 * - Non-admin users see assets from their workgroups only
 * - Workgroup membership is PRIMARY access control
 */
@Singleton
class WorkgroupVulnsService(
    private val workgroupRepository: WorkgroupRepository,
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val entityManager: EntityManager,
    private val importHistoryRepository: CrowdStrikeImportHistoryRepository
) {

    private val logger = LoggerFactory.getLogger(WorkgroupVulnsService::class.java)

    /**
     * Vulnerability counts grouped by severity level.
     * Used internally for validation and aggregation.
     * Internal visibility allows testing while keeping encapsulation.
     *
     * @property total Total vulnerability count
     * @property critical Count of CRITICAL severity vulnerabilities
     * @property high Count of HIGH severity vulnerabilities
     * @property medium Count of MEDIUM severity vulnerabilities
     * @property low Count of LOW severity vulnerabilities
     * @property unknown Count of vulnerabilities with NULL or non-standard severity
     */
    internal data class SeverityCounts(
        val total: Int,
        val critical: Int,
        val high: Int,
        val medium: Int,
        val low: Int,
        val unknown: Int
    ) {
        /**
         * Check if severity counts sum to total.
         *
         * @return true if validation passed, false if mismatch detected
         */
        fun isValid(): Boolean {
            val sum = critical + high + medium + low + unknown
            return sum == total
        }
    }

    /**
     * Count vulnerabilities grouped by severity level for given assets.
     * Reuses the same logic as AccountVulnsService for consistency.
     *
     * Uses SQL query with conditional aggregation (CASE statements) for performance.
     * Normalizes severity values to uppercase for consistent matching.
     *
     * @param assetIds List of asset IDs to count vulnerabilities for
     * @return Map of asset ID to SeverityCounts with severity breakdown
     */
    private fun countVulnerabilitiesBySeverity(assetIds: List<Long>): Map<Long, SeverityCounts> {
        if (assetIds.isEmpty()) {
            return emptyMap()
        }

        logger.debug("Counting vulnerabilities by severity for {} assets", assetIds.size)

        // Build native SQL query with conditional aggregation
        // Note: Using COALESCE to handle NULL severity as empty string
        val sql = """
            SELECT 
                v.asset_id,
                COUNT(*) as total_count,
                SUM(CASE WHEN UPPER(COALESCE(v.cvss_severity, '')) = 'CRITICAL' THEN 1 ELSE 0 END) as critical_count,
                SUM(CASE WHEN UPPER(COALESCE(v.cvss_severity, '')) = 'HIGH' THEN 1 ELSE 0 END) as high_count,
                SUM(CASE WHEN UPPER(COALESCE(v.cvss_severity, '')) = 'MEDIUM' THEN 1 ELSE 0 END) as medium_count,
                SUM(CASE WHEN UPPER(COALESCE(v.cvss_severity, '')) = 'LOW' THEN 1 ELSE 0 END) as low_count,
                SUM(CASE WHEN COALESCE(v.cvss_severity, '') = '' 
                         OR UPPER(v.cvss_severity) NOT IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW') 
                    THEN 1 ELSE 0 END) as unknown_count
            FROM vulnerability v
            WHERE v.asset_id IN (:assetIds)
            GROUP BY v.asset_id
        """.trimIndent()

        try {
            // Execute native query using EntityManager
            val query = entityManager.createNativeQuery(sql)
            query.setParameter("assetIds", assetIds)
            
            @Suppress("UNCHECKED_CAST")
            val results = query.resultList as List<Array<Any>>
            
            val severityMap = results.associate { row ->
                val assetId = (row[0] as Number).toLong()
                val counts = SeverityCounts(
                    total = (row[1] as Number).toInt(),
                    critical = (row[2] as Number).toInt(),
                    high = (row[3] as Number).toInt(),
                    medium = (row[4] as Number).toInt(),
                    low = (row[5] as Number).toInt(),
                    unknown = (row[6] as Number).toInt()
                )
                
                // Validate counts and log if mismatch
                if (!counts.isValid()) {
                    val sum = counts.critical + counts.high + counts.medium + counts.low + counts.unknown
                    logger.error(
                        "Severity count mismatch for asset {}: sum={}, total={} " +
                        "(critical={}, high={}, medium={}, low={}, unknown={})",
                        assetId, sum, counts.total, counts.critical, counts.high, 
                        counts.medium, counts.low, counts.unknown
                    )
                }
                
                assetId to counts
            }
            
            logger.debug("Severity counting complete: {} assets with vulnerability data", severityMap.size)
            return severityMap
            
        } catch (e: Exception) {
            logger.error("Error counting vulnerabilities by severity", e)
            // Return empty map on error - severity fields will be null (backward compatible)
            return emptyMap()
        }
    }

    /**
     * Get vulnerability summary for the authenticated user's workgroups.
     *
     * @param authentication User authentication details (email + roles)
     * @return WorkgroupVulnsSummaryDto with workgroup groups, assets, and vulnerability counts
     * @throws IllegalStateException if user has ADMIN role or email not found in authentication
     * @throws NoSuchElementException if user has no workgroup memberships
     */
    fun getWorkgroupVulnsSummary(authentication: Authentication): WorkgroupVulnsSummaryDto {
        // Extract email from authentication attributes
        val userEmail = authentication.attributes["email"]?.toString()
            ?: throw IllegalStateException("Email not found in authentication context")
        val roles = authentication.roles

        logger.debug("Getting workgroup vulns summary for user: {} (username: {})", userEmail, authentication.name)

        // Check if user is admin - reject with error
        if (roles.contains("ADMIN")) {
            logger.warn("Admin user {} attempted to access WG Vulns view", userEmail)
            throw IllegalStateException("Admin users should use System Vulns view instead")
        }

        // Get user's workgroups from workgroup membership
        val userWorkgroups = workgroupRepository.findWorkgroupsByUserEmail(userEmail)

        // Check if user has any workgroup memberships
        if (userWorkgroups.isEmpty()) {
            logger.warn("User {} has no workgroup memberships", userEmail)
            throw NoSuchElementException("You are not a member of any workgroups. Please contact your administrator.")
        }

        logger.debug("User {} is a member of {} workgroups", userEmail, userWorkgroups.size)

        // Get workgroup IDs
        val workgroupIds = userWorkgroups.mapNotNull { it.id }

        // Get all assets in user's workgroups
        val assets = assetRepository.findByWorkgroupIdIn(workgroupIds)

        logger.debug("Found {} assets for user {} across {} workgroups", 
            assets.size, userEmail, userWorkgroups.size)

        // Get severity counts for all assets
        val assetIds = assets.mapNotNull { it.id }
        val severityCountsMap = countVulnerabilitiesBySeverity(assetIds)
        
        // Group assets by workgroup
        // Note: An asset can belong to multiple workgroups, so we handle this carefully
        val assetsByWorkgroup = assets.groupBy { asset ->
            // Get all workgroups for this asset that the user is a member of
            asset.workgroups.filter { wg -> wg.id in workgroupIds }
        }.flatMap { (workgroups, assetsInGroup) ->
            // Create a pair for each workgroup-asset combination
            workgroups.map { wg -> wg to assetsInGroup }
        }.groupBy({ it.first }, { it.second })
        .mapValues { it.value.flatten().distinct() }

        // Build workgroup groups for ALL user's workgroups (including those with no assets)
        val workgroupGroups = userWorkgroups.map { workgroup ->
            val workgroupAssets = assetsByWorkgroup[workgroup] ?: emptyList()
            
            // Sort assets by vulnerability count (descending)
            val sortedAssets = workgroupAssets
                .map { asset ->
                    val assetId = asset.id!!
                    val severityCounts = severityCountsMap[assetId]
                    
                    AssetVulnCountDto(
                        id = assetId,
                        name = asset.name,
                        type = asset.type,
                        vulnerabilityCount = severityCounts?.total ?: 0,
                        criticalCount = severityCounts?.critical,
                        highCount = severityCounts?.high,
                        mediumCount = severityCounts?.medium
                    )
                }
                .sortedByDescending { it.vulnerabilityCount }

            // Aggregate severity totals at workgroup level
            val totalCritical = sortedAssets.sumOf { it.criticalCount ?: 0 }
            val totalHigh = sortedAssets.sumOf { it.highCount ?: 0 }
            val totalMedium = sortedAssets.sumOf { it.mediumCount ?: 0 }

            WorkgroupGroupDto(
                workgroupId = workgroup.id!!,
                workgroupName = workgroup.name,
                workgroupDescription = workgroup.description,
                assets = sortedAssets,
                totalAssets = sortedAssets.size,
                totalVulnerabilities = sortedAssets.sumOf { it.vulnerabilityCount },
                totalCritical = totalCritical,
                totalHigh = totalHigh,
                totalMedium = totalMedium
            )
        }
        // Sort workgroup groups alphabetically by name
        .sortedBy { it.workgroupName }

        // Calculate overall totals (deduplicating assets that appear in multiple workgroups)
        val uniqueAssets = workgroupGroups.flatMap { it.assets }.distinctBy { it.id }
        val totalAssets = uniqueAssets.size
        val totalVulnerabilities = uniqueAssets.sumOf { it.vulnerabilityCount }
        
        // Calculate global severity totals (using unique assets)
        val globalCritical = uniqueAssets.sumOf { it.criticalCount ?: 0 }
        val globalHigh = uniqueAssets.sumOf { it.highCount ?: 0 }
        val globalMedium = uniqueAssets.sumOf { it.mediumCount ?: 0 }

        logger.debug(
            "Returning summary: {} workgroup groups, {} unique assets, {} total vulnerabilities " +
            "({} critical, {} high, {} medium)",
            workgroupGroups.size, totalAssets, totalVulnerabilities, 
            globalCritical, globalHigh, globalMedium
        )

        val latestImport = importHistoryRepository.findLatest()
            ?.let { CrowdStrikeImportStatusDto.fromEntity(it) }

        return WorkgroupVulnsSummaryDto(
            workgroupGroups = workgroupGroups,
            totalAssets = totalAssets,
            totalVulnerabilities = totalVulnerabilities,
            globalCritical = globalCritical,
            globalHigh = globalHigh,
            globalMedium = globalMedium,
            lastImport = latestImport
        )
    }
}
