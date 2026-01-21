package com.secman.cli.service

import com.secman.repository.AssetRepository
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.UserMappingRepository
import com.secman.service.NotificationService
import io.micronaut.data.model.Pageable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * CLI-specific notification service that bridges CLI command to backend NotificationService
 * Feature 035: Outdated Asset Notification System
 */
@Singleton
class NotificationCliService(
    private val notificationService: NotificationService,
    private val outdatedAssetRepository: OutdatedAssetMaterializedViewRepository,
    private val assetRepository: AssetRepository,
    private val userMappingRepository: UserMappingRepository
) {
    private val logger = LoggerFactory.getLogger(NotificationCliService::class.java)

    /**
     * Process outdated asset notifications
     * This queries the OutdatedAssetMaterializedView and sends emails
     *
     * @param dryRun If true, report planned emails without sending
     * @param verbose If true, log detailed per-asset information
     * @return Processing result
     */
    fun processOutdatedNotifications(
        dryRun: Boolean,
        verbose: Boolean
    ): NotificationService.ProcessingResult {

        if (verbose) {
            logger.info("Starting notification processing (dry-run: $dryRun)")
        }

        // Query outdated assets from materialized view
        val outdatedAssets = queryOutdatedAssets()

        if (verbose) {
            logger.info("Found ${outdatedAssets.size} outdated assets")
            outdatedAssets.forEach { asset ->
                logger.info("  - ${asset.assetName} (${asset.assetType}): ${asset.vulnerabilityCount} vulns, " +
                        "oldest ${asset.oldestVulnDays} days, owner: ${asset.ownerEmail}")
            }
        }

        // Process through NotificationService
        val result = notificationService.processOutdatedAssets(outdatedAssets, dryRun)

        if (verbose) {
            logger.info("Processing complete: ${result.emailsSent} emails sent, ${result.failures} failures")
        }

        return result
    }

    /**
     * Query outdated assets from materialized view and join with Asset/UserMapping to get owner email
     *
     * Query logic:
     * 1. Get all outdated assets from materialized view (totalOverdueCount > 0)
     * 2. For each asset, lookup Asset entity to get owner (AWS account ID)
     * 3. Lookup UserMapping to resolve AWS account ID to email
     * 4. Map to NotificationService.OutdatedAssetData
     */
    private fun queryOutdatedAssets(): List<NotificationService.OutdatedAssetData> {
        // Get all outdated assets (unpaged query)
        val pageable = Pageable.unpaged()
        val outdatedView = outdatedAssetRepository.findOutdatedAssets(
            workgroupId = null,  // Get all, no workgroup filtering
            searchTerm = null,
            minSeverity = null,
            adDomain = null,
            pageable = pageable
        )

        val results = mutableListOf<NotificationService.OutdatedAssetData>()

        outdatedView.content.forEach { view ->
            // Lookup asset to get owner
            val asset = assetRepository.findById(view.assetId).orElse(null)
            if (asset == null) {
                logger.warn("Asset ${view.assetId} not found, skipping")
                return@forEach
            }

            // Lookup owner email from UserMapping (owner is AWS account ID)
            val userMappings = userMappingRepository.findByAwsAccountId(asset.owner)
            if (userMappings.isEmpty()) {
                logger.warn("No email found for AWS account ${asset.owner}, skipping asset ${asset.name}")
                return@forEach
            }

            // Use first mapping's email (typically one email per AWS account)
            val ownerEmail = userMappings.first().email

            // Determine highest severity
            val severity = when {
                view.criticalCount > 0 -> "CRITICAL"
                view.highCount > 0 -> "HIGH"
                view.mediumCount > 0 -> "MEDIUM"
                else -> "LOW"
            }

            // Feature 039: Get asset criticality (effective criticality includes inheritance)
            val criticality = asset.getEffectiveCriticality().name

            results.add(
                NotificationService.OutdatedAssetData(
                    assetId = view.assetId,
                    assetName = view.assetName,
                    assetType = view.assetType,
                    ownerEmail = ownerEmail,
                    vulnerabilityCount = view.totalOverdueCount,
                    oldestVulnDays = view.oldestVulnDays,
                    oldestVulnId = view.oldestVulnId ?: "unknown",
                    severity = severity,
                    criticality = criticality
                )
            )
        }

        logger.info("Mapped ${results.size} outdated assets with owner emails")
        return results
    }
}
