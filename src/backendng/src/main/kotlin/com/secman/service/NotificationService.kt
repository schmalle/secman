package com.secman.service

import com.secman.domain.NotificationType
import com.secman.repository.AssetReminderStateRepository
import com.secman.repository.NotificationLogRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Core notification orchestration service
 * Feature 035: Outdated Asset Notification System
 */
@Singleton
class NotificationService(
    private val emailTemplateService: EmailTemplateService,
    private val reminderStateService: ReminderStateService,
    private val notificationLogService: NotificationLogService,
    private val emailSender: EmailSender,
    private val assetReminderStateRepository: AssetReminderStateRepository
) {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    companion object {
        const val DEFAULT_DASHBOARD_URL = "http://localhost:4321/assets"
    }

    /**
     * Process outdated assets and send email notifications
     * This is the main entry point called by the CLI command
     *
     * @param outdatedAssets List of outdated assets with owner information
     * @param dryRun If true, report planned emails without sending
     * @return Processing result with statistics
     */
    fun processOutdatedAssets(
        outdatedAssets: List<OutdatedAssetData>,
        dryRun: Boolean = false
    ): ProcessingResult {
        logger.info("Processing ${outdatedAssets.size} outdated assets (dry-run: $dryRun)")

        val assetsByOwner = aggregateByOwner(outdatedAssets)
        logger.info("Aggregated into ${assetsByOwner.size} owners")

        var emailsSent = 0
        var failures = 0
        val skipped = mutableListOf<String>()

        assetsByOwner.forEach { (ownerEmail, assets) ->
            if (ownerEmail.isBlank()) {
                logger.warn("Skipping assets with blank owner email")
                skipped.add("blank email")
                return@forEach
            }

            try {
                // Determine reminder level for each asset
                val assetsWithLevels = assets.map { asset ->
                    val state = reminderStateService.getOrCreateReminderState(asset.assetId, true)
                    asset to (state?.level ?: 1)
                }

                // Check if we should send today (duplicate prevention)
                val firstAsset = assets.first()
                if (!reminderStateService.shouldSendToday(firstAsset.assetId)) {
                    logger.info("Skipping $ownerEmail - already notified today")
                    skipped.add(ownerEmail)
                    return@forEach
                }

                // Determine which reminder level to use (use highest level)
                val maxLevel = assetsWithLevels.maxOf { it.second }
                val notificationType = if (maxLevel == 2) {
                    NotificationType.OUTDATED_LEVEL2
                } else {
                    NotificationType.OUTDATED_LEVEL1
                }

                if (dryRun) {
                    logger.info("[DRY-RUN] Would send $notificationType to $ownerEmail for ${assets.size} assets")
                } else {
                    sendOutdatedReminder(ownerEmail, assets, notificationType)
                    assets.forEach { asset ->
                        reminderStateService.updateLastSent(asset.assetId)
                    }
                }

                emailsSent++
            } catch (e: Exception) {
                logger.error("Failed to process notification for $ownerEmail", e)
                failures++

                if (!dryRun) {
                    // Log failure for first asset (representative)
                    notificationLogService.logFailure(
                        assetId = assets.first().assetId,
                        assetName = assets.first().assetName,
                        ownerEmail = ownerEmail,
                        notificationType = NotificationType.OUTDATED_LEVEL1,
                        errorMessage = e.message ?: "Unknown error"
                    )
                }
            }
        }

        return ProcessingResult(
            assetsProcessed = outdatedAssets.size,
            emailsSent = emailsSent,
            failures = failures,
            skipped = skipped
        )
    }

    /**
     * Aggregate assets by owner email (one email per owner)
     */
    private fun aggregateByOwner(assets: List<OutdatedAssetData>): Map<String, List<OutdatedAssetData>> {
        return assets.groupBy { it.ownerEmail }
    }

    /**
     * Send outdated reminder email to an asset owner
     * Feature 039: Sort assets by criticality (CRITICAL first) for prioritized display
     */
    private fun sendOutdatedReminder(
        ownerEmail: String,
        assets: List<OutdatedAssetData>,
        notificationType: NotificationType
    ) {
        // Calculate severity breakdown
        val severityCounts = assets.groupingBy { it.severity }.eachCount()

        // Feature 039: Sort assets by criticality (CRITICAL > HIGH > MEDIUM > LOW)
        val criticalityOrder = mapOf("CRITICAL" to 0, "HIGH" to 1, "MEDIUM" to 2, "LOW" to 3)
        val sortedAssets = assets.sortedBy { criticalityOrder[it.criticality] ?: 999 }

        // Build email context
        val context = EmailTemplateService.EmailContext(
            recipientEmail = ownerEmail,
            recipientName = null, // TODO: Look up from User table
            assets = sortedAssets.map { asset ->
                EmailTemplateService.AssetEmailData(
                    id = asset.assetId,
                    name = asset.assetName,
                    type = asset.assetType,
                    vulnerabilityCount = asset.vulnerabilityCount,
                    oldestVulnDays = asset.oldestVulnDays,
                    oldestVulnId = asset.oldestVulnId,
                    criticality = asset.criticality // Feature 039: Include criticality
                )
            },
            notificationType = notificationType,
            reminderLevel = if (notificationType == NotificationType.OUTDATED_LEVEL1) 1 else 2,
            totalCount = assets.size,
            criticalCount = severityCounts.getOrDefault("CRITICAL", 0),
            highCount = severityCounts.getOrDefault("HIGH", 0),
            mediumCount = severityCounts.getOrDefault("MEDIUM", 0),
            lowCount = severityCounts.getOrDefault("LOW", 0),
            dashboardUrl = DEFAULT_DASHBOARD_URL
        )

        // Render email template
        val templateName = if (notificationType == NotificationType.OUTDATED_LEVEL1) {
            "outdated-reminder-level1"
        } else {
            "outdated-reminder-level2"
        }

        val htmlBody = emailTemplateService.renderHtml(templateName, context)
        val textBody = emailTemplateService.renderPlainText(context)

        // Build and send email using custom EmailSender
        val emailMessage = EmailSender.EmailMessage(
            to = ownerEmail,
            subject = getSubjectLine(notificationType, assets.size),
            htmlBody = htmlBody,
            plainTextBody = textBody
        )

        val result = emailSender.sendEmail(emailMessage)

        if (result.success) {
            logger.info("Sent $notificationType to $ownerEmail for ${assets.size} assets (attempts: ${result.attemptCount})")

            // Log success for each asset
            assets.forEach { asset ->
                notificationLogService.logSuccess(
                    assetId = asset.assetId,
                    assetName = asset.assetName,
                    ownerEmail = ownerEmail,
                    notificationType = notificationType
                )
            }
        } else {
            logger.error("Failed to send email to $ownerEmail: ${result.errorMessage}")
            throw Exception(result.errorMessage ?: "Email send failed")
        }
    }

    private fun getSubjectLine(notificationType: NotificationType, assetCount: Int): String {
        return when (notificationType) {
            NotificationType.OUTDATED_LEVEL1 -> "Action Requested: $assetCount Outdated Asset(s) Detected"
            NotificationType.OUTDATED_LEVEL2 -> "⚠️ URGENT: $assetCount Outdated Asset(s) Require Immediate Action"
            NotificationType.NEW_VULNERABILITY -> "New Vulnerabilities Detected: $assetCount Vulnerability(ies)"
        }
    }

    /**
     * Data class representing an outdated asset with owner information
     * Feature 039: Added criticality field for asset prioritization
     */
    data class OutdatedAssetData(
        val assetId: Long,
        val assetName: String,
        val assetType: String,
        val ownerEmail: String,
        val vulnerabilityCount: Int,
        val oldestVulnDays: Int,
        val oldestVulnId: String,
        val severity: String, // Vulnerability severity: CRITICAL, HIGH, MEDIUM, LOW
        val criticality: String // Asset criticality: CRITICAL, HIGH, MEDIUM, LOW (Feature 039)
    )

    /**
     * Process new vulnerability notifications for users who have opted in
     * This is called after new vulnerabilities are imported via CLI
     *
     * @param dryRun If true, report planned emails without sending
     * @return Processing result with statistics
     */
    fun processNewVulnerabilityNotifications(dryRun: Boolean = false): ProcessingResult {
        logger.info("Processing new vulnerability notifications (dry-run: $dryRun)")

        // TODO: Implement new vulnerability detection logic
        // 1. Query vulnerabilities added since last notification check
        // 2. Group by asset owner
        // 3. Check user preferences (enableNewVulnNotifications)
        // 4. Send aggregated emails
        // 5. Update lastVulnNotificationSentAt timestamp

        logger.warn("New vulnerability notification logic not yet implemented")
        return ProcessingResult(
            assetsProcessed = 0,
            emailsSent = 0,
            failures = 0,
            skipped = emptyList()
        )
    }

    /**
     * Processing result statistics
     */
    data class ProcessingResult(
        val assetsProcessed: Int,
        val emailsSent: Int,
        val failures: Int,
        val skipped: List<String>
    )
}
