package com.secman.service

import com.secman.dto.AccountGroupDto
import com.secman.dto.AccountVulnsSummaryDto
import com.secman.dto.AssetVulnCountDto
import com.secman.repository.AssetRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
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
    private val vulnerabilityRepository: VulnerabilityRepository
) {

    private val logger = LoggerFactory.getLogger(AccountVulnsService::class.java)

    /**
     * Get vulnerability summary for the authenticated user's AWS accounts.
     *
     * @param authentication User authentication details (email + roles)
     * @return AccountVulnsSummaryDto with account groups, assets, and vulnerability counts
     * @throws IllegalStateException if user has ADMIN role
     * @throws NoSuchElementException if user has no AWS account mappings
     */
    fun getAccountVulnsSummary(authentication: Authentication): AccountVulnsSummaryDto {
        val userEmail = authentication.name
        val roles = authentication.roles

        logger.debug("Getting account vulns summary for user: {}", userEmail)

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

        logger.debug("Found {} assets across {} AWS accounts", assets.size, awsAccountIds.size)

        // Group assets by AWS account ID
        val assetsByAccount = assets.groupBy { it.cloudAccountId ?: "" }
            .filterKeys { it.isNotEmpty() }

        // Build account groups with vulnerability counts
        val accountGroups = assetsByAccount.map { (awsAccountId, accountAssets) ->
            // Sort assets by vulnerability count (descending)
            val sortedAssets = accountAssets
                .map { asset ->
                    AssetVulnCountDto(
                        id = asset.id!!,
                        name = asset.name,
                        type = asset.type,
                        vulnerabilityCount = asset.vulnerabilities.size
                    )
                }
                .sortedByDescending { it.vulnerabilityCount }

            AccountGroupDto(
                awsAccountId = awsAccountId,
                assets = sortedAssets,
                totalAssets = sortedAssets.size,
                totalVulnerabilities = sortedAssets.sumOf { it.vulnerabilityCount }
            )
        }
        // Sort account groups by AWS account ID (ascending)
        .sortedBy { it.awsAccountId }

        // Calculate overall totals
        val totalAssets = accountGroups.sumOf { it.totalAssets }
        val totalVulnerabilities = accountGroups.sumOf { it.totalVulnerabilities }

        logger.debug("Returning summary: {} accounts, {} total assets, {} total vulnerabilities",
            accountGroups.size, totalAssets, totalVulnerabilities)

        return AccountVulnsSummaryDto(
            accountGroups = accountGroups,
            totalAssets = totalAssets,
            totalVulnerabilities = totalVulnerabilities
        )
    }
}
