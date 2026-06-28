package com.secman.service

import com.secman.domain.NotificationType
import com.secman.domain.User
import com.secman.repository.AssetReminderStateRepository
import com.secman.repository.NotificationLogRepository
import com.secman.repository.UserRepository
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
    private val assetReminderStateRepository: AssetReminderStateRepository,
    private val userRepository: UserRepository
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
        logger.info("Aggregated into ${assetsByOwner.size} recipients")

        // Duplicate-prevention and reminder-level progression are tracked PER ASSET, but a
        // single asset can now fan out to multiple recipients (owner mapping + workgroup
        // members + AWS-account-sharing targets). Evaluate "already sent today" and the
        // reminder level ONCE per distinct asset, up front, before any send updates the
        // per-asset lastSentAt — otherwise notifying the first recipient would silently
        // suppress the same asset for every other recipient who shares it.
        val distinctAssetIds = outdatedAssets.map { it.assetId }.toSet()
        val levelByAssetId = mutableMapOf<Long, Int>()
        val eligibleAssetIds = mutableSetOf<Long>()
        distinctAssetIds.forEach { assetId ->
            val state = reminderStateService.getOrCreateReminderState(assetId, true)
            levelByAssetId[assetId] = state?.level ?: 1
            if (reminderStateService.shouldSendToday(assetId)) {
                eligibleAssetIds.add(assetId)
            }
        }

        var emailsSent = 0
        var failures = 0
        val skipped = mutableListOf<String>()
        // Assets successfully delivered to at least one recipient — marked sent-today once,
        // after all recipients are processed.
        val sentAssetIds = mutableSetOf<Long>()

        assetsByOwner.forEach { (ownerEmail, assets) ->
            if (ownerEmail.isBlank()) {
                logger.warn("Skipping assets with blank recipient email")
                skipped.add("blank email")
                return@forEach
            }

            // Only assets not already notified today (across all recipients) are sent.
            val eligibleAssets = assets.filter { eligibleAssetIds.contains(it.assetId) }
            if (eligibleAssets.isEmpty()) {
                logger.info("Skipping $ownerEmail - no assets eligible to send today")
                skipped.add(ownerEmail)
                return@forEach
            }

            try {
                // Determine which reminder level to use (use highest level)
                val maxLevel = eligibleAssets.maxOf { levelByAssetId[it.assetId] ?: 1 }
                val notificationType = if (maxLevel == 2) {
                    NotificationType.OUTDATED_LEVEL2
                } else {
                    NotificationType.OUTDATED_LEVEL1
                }

                if (dryRun) {
                    logger.info("[DRY-RUN] Would send $notificationType to $ownerEmail for ${eligibleAssets.size} assets")
                } else {
                    sendOutdatedReminder(ownerEmail, eligibleAssets, notificationType)
                    sentAssetIds.addAll(eligibleAssets.map { it.assetId })
                }

                emailsSent++
            } catch (e: Exception) {
                logger.error("Failed to process notification for $ownerEmail", e)
                failures++

                if (!dryRun) {
                    // Log failure for first asset (representative)
                    notificationLogService.logFailure(
                        assetId = eligibleAssets.first().assetId,
                        assetName = eligibleAssets.first().assetName,
                        ownerEmail = ownerEmail,
                        notificationType = NotificationType.OUTDATED_LEVEL1,
                        errorMessage = e.message ?: "Unknown error"
                    )
                }
            }
        }

        // Mark each delivered asset as sent exactly once, regardless of how many
        // recipients it fanned out to.
        if (!dryRun) {
            sentAssetIds.forEach { reminderStateService.updateLastSent(it) }
        }

        // Send notifications to users with REPORT role
        val reportEmailResult = sendToReportUsers(outdatedAssets, dryRun)
        emailsSent += reportEmailResult.first
        failures += reportEmailResult.second

        return ProcessingResult(
            assetsProcessed = outdatedAssets.size,
            emailsSent = emailsSent,
            failures = failures,
            skipped = skipped
        )
    }

    /**
     * Send outdated asset notifications to all users with the REPORT role.
     * REPORT users receive a consolidated notification with all outdated assets.
     *
     * @return Pair of (emailsSent, failures)
     */
    private fun sendToReportUsers(
        outdatedAssets: List<OutdatedAssetData>,
        dryRun: Boolean
    ): Pair<Int, Int> {
        if (outdatedAssets.isEmpty()) return Pair(0, 0)

        val reportUsers = userRepository.findByRolesContaining(User.Role.REPORT)
        if (reportUsers.isEmpty()) {
            logger.debug("No users with REPORT role found, skipping report notifications")
            return Pair(0, 0)
        }

        logger.info("Sending outdated asset notifications to ${reportUsers.size} REPORT user(s)")

        var emailsSent = 0
        var failures = 0

        val notificationType = NotificationType.OUTDATED_LEVEL1

        reportUsers.forEach { reportUser ->
            try {
                if (reportUser.email.isNullOrBlank()) {
                    logger.warn("REPORT user ${reportUser.username} has no email, skipping")
                    return@forEach
                }

                if (dryRun) {
                    logger.info("[DRY-RUN] Would send outdated asset report to REPORT user ${reportUser.email} for ${outdatedAssets.size} assets")
                } else {
                    sendOutdatedReminder(reportUser.email, outdatedAssets, notificationType)
                }

                emailsSent++
            } catch (e: Exception) {
                logger.error("Failed to send report notification to ${reportUser.email}", e)
                failures++
            }
        }

        return Pair(emailsSent, failures)
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
