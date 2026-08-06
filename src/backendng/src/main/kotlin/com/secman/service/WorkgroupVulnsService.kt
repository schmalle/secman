package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.Workgroup
import com.secman.dto.AssetVulnCountDto
import com.secman.dto.CrowdStrikeImportStatusDto
import com.secman.dto.WorkgroupGroupDto
import com.secman.dto.WorkgroupVulnsSummaryDto
import com.secman.dto.AssetInterventionStatus
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.WorkgroupRepository
import com.secman.repository.CrowdStrikeImportHistoryRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

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
 * - Non-admin users see assets from their workgroups (direct membership) AND
 *   assets whose cloudAccountId matches an AWS account assigned to one of their
 *   workgroups via WorkgroupAwsAccount (Unified Asset Access rule #9).
 */
@Singleton
class WorkgroupVulnsService(
    private val workgroupRepository: WorkgroupRepository,
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val assetVulnCountsQuery: AssetVulnCountsQuery,
    private val importHistoryRepository: CrowdStrikeImportHistoryRepository,
    private val workgroupAwsAccountRepository: WorkgroupAwsAccountRepository
) {

    private val logger = LoggerFactory.getLogger(WorkgroupVulnsService::class.java)

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

        // Get user's *effective* workgroups: direct memberships UNION all
        // descendants (Feature 040). Membership cascades downward so a member
        // of an L2 workgroup sees the L3 sub-teams and their vulnerable assets
        // here. AWS-account-driven access (rule #9) likewise picks up the
        // descendants' linked accounts because they are included in this set.
        val userWorkgroups = workgroupRepository.findEffectiveWorkgroupsByUserEmail(userEmail)

        // Check if user has any workgroup memberships
        if (userWorkgroups.isEmpty()) {
            logger.warn("User {} has no workgroup memberships", userEmail)
            throw NoSuchElementException("You are not a member of any workgroups. Please contact your administrator.")
        }

        logger.debug("User {} is a member of {} workgroups", userEmail, userWorkgroups.size)

        // Get workgroup IDs
        val workgroupIds = userWorkgroups.mapNotNull { it.id }

        // Direct-membership assets (asset ↔ workgroup join). The repository query
        // uses LEFT JOIN FETCH on workgroups, so asset.workgroups is safe to read.
        val directAssets = assetRepository.findByWorkgroupIdIn(workgroupIds)
        val directAssetIds = directAssets.mapNotNullTo(HashSet()) { it.id }

        // Unified Asset Access rule #9: assets in AWS accounts assigned to the
        // user's workgroups (WorkgroupAwsAccount, direct workgroup membership only).
        // Build workgroupId -> set<awsAccountId> so we can also map each EC2 asset
        // back to the workgroup column it should appear under.
        val workgroupToAwsAccounts: Map<Long, Set<String>> = workgroupIds.associateWith { wgId ->
            workgroupAwsAccountRepository.findByWorkgroupId(wgId)
                .map { it.awsAccountId }
                .toSet()
        }
        val allWorkgroupCloudAccountIds = workgroupToAwsAccounts.values
            .flatten()
            .distinct()
        val cloudAssets = if (allWorkgroupCloudAccountIds.isNotEmpty()) {
            assetRepository.findByCloudAccountIdIn(allWorkgroupCloudAccountIds)
        } else {
            emptyList()
        }

        // distinctBy keeps the first occurrence — directAssets first ensures the
        // version with the eagerly-fetched workgroups collection wins, avoiding
        // a lazy read on cloud-only assets later.
        val assets = (directAssets + cloudAssets).distinctBy { it.id }

        logger.debug(
            "Found {} assets for user {} ({} direct, {} via workgroup AWS accounts) across {} workgroups",
            assets.size, userEmail, directAssets.size, cloudAssets.size, userWorkgroups.size
        )

        // Get severity counts for all assets
        val assetIds = assets.mapNotNull { it.id }
        // One threshold instant for the whole response, so every asset is measured from the same
        // moment. Shared with the Account Vulns view via AssetVulnCountsQuery — this view
        // previously had its own copy of the query that omitted the exception columns entirely.
        val thresholdDays = assetVulnCountsQuery.thresholdDays()
        val thresholdDate = LocalDateTime.now().minusDays(thresholdDays.toLong())
        val severityCountsMap = assetVulnCountsQuery.countByAsset(assetIds, thresholdDate)

        // Map every accessible asset to the user-workgroups it should appear under.
        // For direct-membership assets we read asset.workgroups (fetched eagerly).
        // For cloud-only assets we MUST NOT read asset.workgroups (lazy proxy from
        // findByCloudAccountIdIn) — match via cloudAccountId instead.
        val workgroupById: Map<Long, Workgroup> = userWorkgroups.associateBy { it.id!! }
        val assetWorkgroups: Map<Long, List<Workgroup>> = assets.mapNotNull { asset ->
            val id = asset.id ?: return@mapNotNull null
            val directWgs: List<Workgroup> = if (id in directAssetIds) {
                asset.workgroups.filter { it.id in workgroupIds }
            } else {
                emptyList()
            }
            val cloudWgs: List<Workgroup> = asset.cloudAccountId?.let { cid ->
                workgroupToAwsAccounts
                    .filterValues { cid in it }
                    .keys
                    .mapNotNull { workgroupById[it] }
            } ?: emptyList()
            id to (directWgs + cloudWgs).distinctBy { it.id }
        }.toMap()

        val assetsByWorkgroup: Map<Workgroup, List<Asset>> = userWorkgroups.associateWith { wg ->
            assets.filter { asset -> assetWorkgroups[asset.id]?.any { it.id == wg.id } == true }
        }

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
                        mediumCount = severityCounts?.medium,
                        exceptedCount = severityCounts?.excepted,
                        nonExceptedCount = severityCounts?.nonExcepted,
                        nonExceptedOverdueCount = severityCounts?.nonExceptedOverdue,
                        status = severityCounts?.status ?: AssetInterventionStatus.GREEN
                    )
                }
                // Worst-first, vulnerability count as tie-break — same ordering as Account Vulns.
                .sortedWith(
                    compareByDescending<AssetVulnCountDto> { it.status?.ordinal ?: 0 }
                        .thenByDescending { it.vulnerabilityCount }
                )

            // Aggregate severity totals at workgroup level
            val totalCritical = sortedAssets.sumOf { it.criticalCount ?: 0 }
            val totalHigh = sortedAssets.sumOf { it.highCount ?: 0 }
            val totalMedium = sortedAssets.sumOf { it.mediumCount ?: 0 }
            val totalExcepted = sortedAssets.sumOf { it.exceptedCount ?: 0 }
            val totalNonExcepted = sortedAssets.sumOf { it.nonExceptedCount ?: 0 }
            val workgroupStatus = AssetInterventionStatus.worstOf(sortedAssets.mapNotNull { it.status })
            val needingAttention = sortedAssets.count {
                it.status != null && it.status != AssetInterventionStatus.GREEN
            }

            WorkgroupGroupDto(
                workgroupId = workgroup.id!!,
                workgroupName = workgroup.name,
                workgroupDescription = workgroup.description,
                assets = sortedAssets,
                totalAssets = sortedAssets.size,
                totalVulnerabilities = sortedAssets.sumOf { it.vulnerabilityCount },
                totalCritical = totalCritical,
                totalHigh = totalHigh,
                totalMedium = totalMedium,
                totalExcepted = totalExcepted,
                totalNonExcepted = totalNonExcepted,
                assetsNeedingAttention = needingAttention,
                status = workgroupStatus
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
            // Deduplicated: an asset in two workgroups must not count twice.
            globalExcepted = uniqueAssets.sumOf { it.exceptedCount ?: 0 },
            globalNonExcepted = uniqueAssets.sumOf { it.nonExceptedCount ?: 0 },
            globalStatus = AssetInterventionStatus.worstOf(uniqueAssets.mapNotNull { it.status }),
            assetsNeedingAttention = uniqueAssets.count {
                it.status != null && it.status != AssetInterventionStatus.GREEN
            },
            thresholdDays = thresholdDays,
            lastImport = latestImport
        )
    }
}
