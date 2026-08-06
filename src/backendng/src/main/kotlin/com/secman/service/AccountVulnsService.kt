package com.secman.service

import com.secman.dto.AccountGroupDto
import com.secman.dto.AccountVulnsSummaryDto
import com.secman.dto.AssetInterventionStatus
import com.secman.dto.AssetVulnCountDto
import com.secman.dto.CrowdStrikeImportStatusDto
import com.secman.repository.UserMappingRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.CrowdStrikeImportHistoryRepository
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

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
 * - Non-admin users see the cloud assets they can access via the SAME unified
 *   10-point filter as the Current Vulnerabilities view (AssetFilterService),
 *   restricted to assets that carry a cloudAccountId and grouped by account.
 *   This keeps the two views' vulnerability counts reconcilable for every user.
 */
@Singleton
class AccountVulnsService(
    private val userMappingRepository: UserMappingRepository,
    private val assetFilterService: AssetFilterService,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val assetVulnCountsQuery: AssetVulnCountsQuery,
    private val importHistoryRepository: CrowdStrikeImportHistoryRepository,
    private val awsAccountSharingService: AwsAccountSharingService
) {

    private val logger = LoggerFactory.getLogger(AccountVulnsService::class.java)

    companion object {
        /**
         * Upper bound on cloud assets rendered in a single Account Vulns response.
         *
         * Exists because ADMIN/SECCHAMPION reach every asset: AssetFilterService.getAccessibleAssets
         * short-circuits to assetRepository.findAll() for them. One row per asset is unusable in a
         * browser long before this, so the cap fails loudly instead of quietly building a huge page.
         */
        internal const val MAX_RENDERED_ASSETS = 5000
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

        logger.debug("Getting account vulns summary for user: {} (username: {})", userEmail, authentication.name)

        // ADMIN is no longer rejected here: the intervention indicator is only useful if the people
        // who act on it can see it, and admins were previously bounced to System Vulnerabilities,
        // which has no asset-grouped overview. Access still flows through the same unified filter.

        // Account IDs the user is explicitly mapped to (UserMapping) or has shared
        // (AwsAccountSharing). Retained so that mapped-but-empty accounts still render
        // as account groups, matching the historical Account Vulns UX.
        val ownAwsAccountIds = userMappingRepository.findDistinctAwsAccountIdByEmail(userEmail)
        val sharedAwsAccountIds = awsAccountSharingService.getSharedAwsAccountIdsByEmail(userEmail)

        // Single source of truth for *which assets this user can see*: the same unified
        // 10-point filter used by the Current Vulnerabilities view (AssetFilterService).
        // Restricted to cloud assets since this view is organised by AWS account.
        // Using the shared filter guarantees the Account view and the Current view count
        // the same (asset, vulnerability) rows for every access path (workgroup, owner,
        // AD-domain, scan/manual upload, WorkgroupAwsAccount, sharing), not just direct
        // AWS account mappings.
        val assets = assetFilterService.getAccessibleAssets(authentication)
            .filter { !it.cloudAccountId.isNullOrBlank() }

        // ADMIN and SECCHAMPION reach every asset, so this view is only bounded for them by the
        // size of the estate. The response renders one row per asset and holds them all in memory,
        // so refuse up front with actionable guidance rather than building a payload no browser
        // can use. Scoped users are naturally bounded by their access and never hit this.
        if (assets.size > MAX_RENDERED_ASSETS) {
            logger.warn("Account Vulns refused for {}: {} cloud assets exceeds the {} cap",
                userEmail, assets.size, MAX_RENDERED_ASSETS)
            throw IllegalArgumentException(
                "This view would render ${assets.size} assets, above the $MAX_RENDERED_ASSETS limit. " +
                "Use System Vulnerabilities for estate-wide analysis."
            )
        }

        // Account groups = explicitly mapped/shared accounts (so empty accounts still show)
        // ∪ accounts that actually contain an accessible cloud asset.
        val awsAccountIds = (ownAwsAccountIds + sharedAwsAccountIds +
            assets.mapNotNull { it.cloudAccountId }).distinct()

        // 404 only when the user has neither AWS account mappings/shares nor any
        // accessible cloud asset to display.
        if (awsAccountIds.isEmpty()) {
            logger.warn("User {} has no AWS account mappings, shared accounts, or accessible cloud assets", userEmail)
            throw NoSuchElementException("No AWS accounts are mapped or shared with your user account. Please contact your administrator.")
        }

        logger.debug("User {} sees {} cloud assets across {} AWS accounts ({} own-mapped, {} shared)",
            userEmail, assets.size, awsAccountIds.size, ownAwsAccountIds.size, sharedAwsAccountIds.size)

        // Feature 019: Get severity counts for all assets
        val assetIds = assets.mapNotNull { it.id }
        // One threshold instant for the whole response, so every asset is measured from the same
        // moment and two assets cannot straddle the boundary because of clock drift mid-request.
        val thresholdDays = assetVulnCountsQuery.thresholdDays()
        val thresholdDate = LocalDateTime.now().minusDays(thresholdDays.toLong())
        val severityCountsMap = assetVulnCountsQuery.countByAsset(assetIds, thresholdDate)


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
                        mediumCount = severityCounts?.medium,
                        exceptedCount = severityCounts?.excepted,
                        nonExceptedCount = severityCounts?.nonExcepted,
                        nonExceptedOverdueCount = severityCounts?.nonExceptedOverdue,
                        // An asset with no vulnerability rows has no counts row at all, which is
                        // GREEN rather than unknown — there is nothing to act on.
                        status = severityCounts?.status ?: AssetInterventionStatus.GREEN
                    )
                }
                // Worst-first: RED assets surface at the top of each account, which is the whole
                // point of the indicator. Vulnerability count remains the tie-break.
                .sortedWith(
                    compareByDescending<AssetVulnCountDto> { it.status?.ordinal ?: 0 }
                        .thenByDescending { it.vulnerabilityCount }
                )

            // Feature 019: Aggregate severity totals at account level
            val totalCritical = sortedAssets.sumOf { it.criticalCount ?: 0 }
            val totalHigh = sortedAssets.sumOf { it.highCount ?: 0 }
            val totalMedium = sortedAssets.sumOf { it.mediumCount ?: 0 }
            val totalExcepted = sortedAssets.sumOf { it.exceptedCount ?: 0 }
            val totalNonExcepted = sortedAssets.sumOf { it.nonExceptedCount ?: 0 }
            val accountStatus = AssetInterventionStatus.worstOf(sortedAssets.mapNotNull { it.status })
            val needingAttention = sortedAssets.count {
                it.status != null && it.status != AssetInterventionStatus.GREEN
            }

            AccountGroupDto(
                awsAccountId = awsAccountId,
                assets = sortedAssets,
                totalAssets = sortedAssets.size,
                totalVulnerabilities = sortedAssets.sumOf { it.vulnerabilityCount },
                // Feature 019: Add account-level severity aggregation
                totalCritical = totalCritical,
                totalHigh = totalHigh,
                totalMedium = totalMedium,
                totalExcepted = totalExcepted,
                totalNonExcepted = totalNonExcepted,
                assetsNeedingAttention = needingAttention,
                status = accountStatus
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
        val globalExcepted = accountGroups.sumOf { it.totalExcepted ?: 0 }
        val globalNonExcepted = accountGroups.sumOf { it.totalNonExcepted ?: 0 }

        logger.debug("Returning summary: {} account groups (from {} mapped AWS accounts), {} total assets, {} total vulnerabilities " +
            "(Feature 019: {} critical, {} high, {} medium)",
            accountGroups.size, awsAccountIds.size, totalAssets, totalVulnerabilities, 
            globalCritical, globalHigh, globalMedium)

        val latestImport = importHistoryRepository.findLatest()
            ?.let { CrowdStrikeImportStatusDto.fromEntity(it) }

        // Compute actual data freshness from vulnerability import timestamps
        val dataFreshness = if (assetIds.isNotEmpty()) {
            vulnerabilityRepository.findLatestImportTimestampByAssetIds(assetIds.toSet())
        } else null

        return AccountVulnsSummaryDto(
            accountGroups = accountGroups,
            totalAssets = totalAssets,
            totalVulnerabilities = totalVulnerabilities,
            // Feature 019: Add global severity totals
            globalCritical = globalCritical,
            globalHigh = globalHigh,
            globalMedium = globalMedium,
            globalExcepted = globalExcepted,
            globalNonExcepted = globalNonExcepted,
            globalStatus = AssetInterventionStatus.worstOf(accountGroups.mapNotNull { it.status }),
            assetsNeedingAttention = accountGroups.sumOf { it.assetsNeedingAttention ?: 0 },
            thresholdDays = thresholdDays,
            lastImport = latestImport,
            dataFreshness = dataFreshness
        )
    }
}
