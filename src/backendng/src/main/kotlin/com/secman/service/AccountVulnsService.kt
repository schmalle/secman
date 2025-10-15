package com.secman.service

import com.secman.dto.AccountGroupDto
import com.secman.dto.AccountVulnsSummaryDto
import com.secman.dto.AssetVulnCountDto
import com.secman.repository.AssetRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory

/**
 * Service for retrieving vulnerability summaries grouped by AWS account.
 *
 * Provides business logic for the Account Vulns view, including:
 * - Looking up user's AWS account mappings
 * - Filtering assets by AWS account IDs
 * - Counting vulnerabilities per asset
 * - Grouping and sorting results
 *
 * Access Control:
 * - Admin users are rejected (should use System Vulns instead)
 * - Non-admin users see assets from their mapped AWS accounts only
 * - AWS account mapping is PRIMARY access control (workgroup restrictions do not apply)
 */
@Singleton
class AccountVulnsService(
    private val userMappingRepository: UserMappingRepository,
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val entityManager: EntityManager
) {

    private val logger = LoggerFactory.getLogger(AccountVulnsService::class.java)

    /**
     * Vulnerability counts grouped by severity level.
     * Used internally for validation and aggregation (Feature 019).
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
     * Feature 019: Severity breakdown enhancement.
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
     * Get vulnerability summary for the authenticated user's AWS accounts.
     *
     * @param authentication User authentication details (email + roles)
     * @return AccountVulnsSummaryDto with account groups, assets, and vulnerability counts
     * @throws IllegalStateException if user has ADMIN role or email not found in authentication
     * @throws NoSuchElementException if user has no AWS account mappings
     */
    fun getAccountVulnsSummary(authentication: Authentication): AccountVulnsSummaryDto {
        // Extract email from authentication attributes (username is in authentication.name)
        val userEmail = authentication.attributes["email"]?.toString()
            ?: throw IllegalStateException("Email not found in authentication context")
        val roles = authentication.roles

        logger.debug("Getting account vulns summary for user: {} (username: {})", userEmail, authentication.name)

        // Check if user is admin - reject with error
        if (roles.contains("ADMIN")) {
            logger.warn("Admin user {} attempted to access Account Vulns view", userEmail)
            throw IllegalStateException("Admin users should use System Vulns view instead")
        }

        // Get user's AWS account IDs from user_mapping table
        val awsAccountIds = userMappingRepository.findDistinctAwsAccountIdByEmail(userEmail)

        // Check if user has any AWS account mappings
        if (awsAccountIds.isEmpty()) {
            logger.warn("User {} has no AWS account mappings", userEmail)
            throw NoSuchElementException("No AWS accounts are mapped to your user account. Please contact your administrator.")
        }

        logger.debug("User {} has access to {} AWS accounts", userEmail, awsAccountIds.size)

        // Get all assets in user's AWS accounts
        val assets = assetRepository.findByCloudAccountIdIn(awsAccountIds)

        logger.debug("Found {} assets for user {} (AWS accounts: {})", 
            assets.size, userEmail, awsAccountIds.joinToString(", "))

        // Feature 019: Get severity counts for all assets
        val assetIds = assets.mapNotNull { it.id }
        val severityCountsMap = countVulnerabilitiesBySeverity(assetIds)
        
        // Group assets by AWS account ID
        val assetsByAccount = assets.groupBy { it.cloudAccountId ?: "" }
            .filterKeys { it.isNotEmpty() }

        // Build account groups for ALL user's AWS accounts (including those with no assets)
        val accountGroups = awsAccountIds.map { awsAccountId ->
            val accountAssets = assetsByAccount[awsAccountId] ?: emptyList()
            
            // Sort assets by vulnerability count (descending)
            val sortedAssets = accountAssets
                .map { asset ->
                    val assetId = asset.id!!
                    val severityCounts = severityCountsMap[assetId]
                    
                    AssetVulnCountDto(
                        id = assetId,
                        name = asset.name,
                        type = asset.type,
                        // Fix: Use severity counts total instead of lazy-loaded collection
                        vulnerabilityCount = severityCounts?.total ?: 0,
                        // Feature 019: Add severity breakdown
                        criticalCount = severityCounts?.critical,
                        highCount = severityCounts?.high,
                        mediumCount = severityCounts?.medium
                    )
                }
                .sortedByDescending { it.vulnerabilityCount }

            // Feature 019: Aggregate severity totals at account level
            val totalCritical = sortedAssets.sumOf { it.criticalCount ?: 0 }
            val totalHigh = sortedAssets.sumOf { it.highCount ?: 0 }
            val totalMedium = sortedAssets.sumOf { it.mediumCount ?: 0 }

            AccountGroupDto(
                awsAccountId = awsAccountId,
                assets = sortedAssets,
                totalAssets = sortedAssets.size,
                totalVulnerabilities = sortedAssets.sumOf { it.vulnerabilityCount },
                // Feature 019: Add account-level severity aggregation
                totalCritical = totalCritical,
                totalHigh = totalHigh,
                totalMedium = totalMedium
            )
        }
        // Sort account groups by AWS account ID (ascending)
        .sortedBy { it.awsAccountId }

        // Calculate overall totals
        val totalAssets = accountGroups.sumOf { it.totalAssets }
        val totalVulnerabilities = accountGroups.sumOf { it.totalVulnerabilities }
        
        // Feature 019: Calculate global severity totals
        val globalCritical = accountGroups.sumOf { it.totalCritical ?: 0 }
        val globalHigh = accountGroups.sumOf { it.totalHigh ?: 0 }
        val globalMedium = accountGroups.sumOf { it.totalMedium ?: 0 }

        logger.debug("Returning summary: {} account groups (from {} mapped AWS accounts), {} total assets, {} total vulnerabilities " +
            "(Feature 019: {} critical, {} high, {} medium)",
            accountGroups.size, awsAccountIds.size, totalAssets, totalVulnerabilities, 
            globalCritical, globalHigh, globalMedium)

        return AccountVulnsSummaryDto(
            accountGroups = accountGroups,
            totalAssets = totalAssets,
            totalVulnerabilities = totalVulnerabilities,
            // Feature 019: Add global severity totals
            globalCritical = globalCritical,
            globalHigh = globalHigh,
            globalMedium = globalMedium
        )
    }
}
