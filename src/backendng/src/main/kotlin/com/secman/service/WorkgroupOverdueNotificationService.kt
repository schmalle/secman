package com.secman.service

import com.secman.config.AppConfig
import com.secman.domain.NotificationType
import com.secman.dto.NotifiedUserOverview
import com.secman.dto.NotifiedUserStatus
import com.secman.dto.OverdueNotificationRunResult
import com.secman.dto.OverdueRecipient
import com.secman.dto.OverdueRecipientOutcome
import com.secman.dto.OverdueRecipientResult
import com.secman.repository.NotificationLogRepository
import com.secman.repository.UserRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * `show-notified-users` and `notify-users-overdue`: overdue-vulnerability digests for the
 * members of enabled workgroups, via direct asset assignment or a whole-AWS-account grant.
 *
 * Independent of [UserVulnerabilityNotificationService] (`send-notification-users`), which
 * keeps its own recipient rule. Both commands here read the same [WorkgroupOverdueRecipientQuery]
 * result, so the overview is exactly the sender's dry run for one user. Sending is not
 * transactional: each recipient costs an SMTP round-trip. See docs/OVERDUE_NOTIFICATIONS.md.
 */
@Singleton
open class WorkgroupOverdueNotificationService(
    private val recipientQuery: WorkgroupOverdueRecipientQuery,
    private val userRepository: UserRepository,
    private val emailService: EmailService,
    private val templateRenderer: EmailTemplateRenderer,
    private val notificationLogService: NotificationLogService,
    private val notificationLogRepository: NotificationLogRepository,
    private val appConfig: AppConfig
) {
    private val logger = LoggerFactory.getLogger(WorkgroupOverdueNotificationService::class.java)

    internal var clock: Clock = Clock.systemUTC()

    /** Rejected caller input; its message is fixed text and safe to return to the client. */
    class InvalidRequest(message: String) : IllegalArgumentException(message)

    companion object {
        const val SUBJECT = "SecMan: Overdue vulnerabilities on your workgroup assets"
        const val TEMPLATE = "workgroup-overdue-notification"
        const val MIN_DAYS = 1
        const val MAX_DAYS = 3650
        private val EMAIL_SHAPE = Regex("^[^\\s@]+@[^\\s@]+$")

        fun validateDays(days: Int) {
            if (days !in MIN_DAYS..MAX_DAYS) throw InvalidRequest("days must be between $MIN_DAYS and $MAX_DAYS")
        }

        /** Trimmed, lower-cased and shape-checked; throws for input that cannot be an email. */
        fun normalizeRequestedEmail(email: String): String {
            val normalized = WorkgroupOverdueRecipientQuery.normalizeEmail(email)
            if (normalized.length > 255 || !EMAIL_SHAPE.matches(normalized)) throw InvalidRequest("invalid email address")
            return normalized
        }

        fun sanitizeForLog(value: String): String = value.replace(Regex("[\\r\\n\\t]"), "_")
    }

    /** What [run] would send to [email] right now, plus the status explaining an empty result. */
    open fun overview(email: String, thresholdDays: Int): NotifiedUserOverview {
        validateDays(thresholdDays)
        val normalized = normalizeRequestedEmail(email)
        val runWindow = window(thresholdDays)
        fun result(status: NotifiedUserStatus) = NotifiedUserOverview(
            email = normalized, status = status, thresholdDays = thresholdDays,
            observationWindowStartUtc = runWindow.seenStartUtc.toString(),
            observationWindowEndUtc = runWindow.seenEndUtc.toString()
        )

        val user = userRepository.findByEmailIgnoreCase(normalized).orElse(null)
            ?: return result(NotifiedUserStatus.USER_NOT_FOUND)
        val workgroups = recipientQuery.findWorkgroupPaths(user.id!!)
        val base = result(NotifiedUserStatus.USER_DISABLED)
            .copy(userId = user.id, username = user.username, workgroups = workgroups)
        if (!user.enabled) return base

        val hasPath = workgroups.any { it.enabled && (it.assignedAssetCount > 0 || it.awsAccountIds.isNotEmpty()) }
        if (!hasPath) return base.copy(status = NotifiedUserStatus.NO_PATHS)

        val assets = recipientQuery.findRecipients(runWindow, user.id).firstOrNull()?.assets.orEmpty()
        return base.copy(
            status = if (assets.isEmpty()) NotifiedUserStatus.NO_OVERDUE_ASSETS else NotifiedUserStatus.WOULD_BE_NOTIFIED,
            alreadyNotifiedToday = normalized in sentToday(),
            assets = assets,
            totalAssets = assets.size,
            totalOverdue = assets.sumOf { it.overdueCount },
            totalCritical = assets.sumOf { it.criticalCount },
            totalHigh = assets.sumOf { it.highCount }
        )
    }

    /** Resolve every recipient (or only [notificationUser]) and mail each one digest, skipping today's recipients. */
    open fun run(thresholdDays: Int, dryRun: Boolean, notificationUser: String?, actor: String): OverdueNotificationRunResult {
        validateDays(thresholdDays)
        val requested = notificationUser?.takeIf { it.isNotBlank() }?.let { normalizeRequestedEmail(it) }
        val userId = requested?.let { email ->
            userRepository.findByEmailIgnoreCase(email).orElse(null)?.id
                ?: return emptyRun(thresholdDays, dryRun, requested, userExists = false)
        }

        val recipients = recipientQuery.findRecipients(window(thresholdDays), userId)
        val alreadySent = sentToday()
        val results = recipients.map { recipient ->
            val outcome = when {
                recipient.email in alreadySent -> OverdueRecipientOutcome.SKIPPED_ALREADY_NOTIFIED
                dryRun -> OverdueRecipientOutcome.WOULD_SEND
                else -> send(recipient, thresholdDays)
            }
            OverdueRecipientResult(
                email = recipient.email,
                outcome = outcome,
                assetCount = recipient.assets.size,
                overdueCount = recipient.assets.sumOf { it.overdueCount },
                workgroups = recipient.assets.flatMap { asset -> asset.reasons.map { it.workgroupName } }.distinct().sorted()
            )
        }

        val sent = results.count { it.outcome == OverdueRecipientOutcome.SENT }
        val failed = results.count { it.outcome == OverdueRecipientOutcome.FAILED }
        val skipped = results.count { it.outcome == OverdueRecipientOutcome.SKIPPED_ALREADY_NOTIFIED }
        val status = when {
            dryRun -> "DRY_RUN"
            failed == 0 -> "SUCCESS"
            sent == 0 -> "FAILURE"
            else -> "PARTIAL_FAILURE"
        }
        logger.info(
            "notify-users-overdue: actor={}, dryRun={}, days={}, userFilter={}, recipients={}, sent={}, failed={}, skipped={}, status={}",
            sanitizeForLog(actor), dryRun, thresholdDays, requested != null, results.size, sent, failed, skipped, status
        )
        return OverdueNotificationRunResult(
            status = status, dryRun = dryRun, thresholdDays = thresholdDays,
            notificationUser = requested, notificationUserExists = requested?.let { true },
            recipientsResolved = results.size, emailsSent = sent, emailsFailed = failed,
            skipped = skipped, recipients = results
        )
    }

    private fun send(recipient: OverdueRecipient, thresholdDays: Int): OverdueRecipientOutcome {
        val label = "Overdue workgroup digest (${recipient.assets.size} assets)"
        val delivered = try {
            emailService.sendEmailWithInlineImages(
                to = recipient.email,
                subject = SUBJECT,
                textContent = renderText(recipient, thresholdDays),
                htmlContent = renderHtml(recipient, thresholdDays),
                inlineImages = templateRenderer.loadLogoInlineImage()
            ).get()
        } catch (e: Exception) {
            logger.error("Overdue digest delivery raised {} for user {}", e.javaClass.simpleName, recipient.userId)
            false
        }
        return if (delivered) {
            notificationLogService.logSuccess(null, label, recipient.email, NotificationType.OVERDUE_WORKGROUP_DIGEST)
            OverdueRecipientOutcome.SENT
        } else {
            notificationLogService.logFailure(null, label, recipient.email, NotificationType.OVERDUE_WORKGROUP_DIGEST,
                "Email delivery failed")
            OverdueRecipientOutcome.FAILED
        }
    }

    /** HTML part: every user-controlled value is escaped; the prebuilt table is inserted after escaping. */
    internal fun renderHtml(recipient: OverdueRecipient, thresholdDays: Int): String {
        val rows = recipient.assets.joinToString("\n") { asset ->
            val cell = "padding: 8px 10px; border-bottom: 1px solid #e9ecef;"
            val reasons = asset.reasons.joinToString("<br/>") { templateRenderer.escapeHtml(describe(it)) }
            """<tr><td style="$cell">${templateRenderer.escapeHtml(asset.assetName)}</td>""" +
                """<td style="$cell font-family: monospace;">${templateRenderer.escapeHtml(asset.awsAccountId ?: "-")}</td>""" +
                """<td style="$cell text-align: center;">${asset.overdueCount}</td>""" +
                """<td style="$cell text-align: center;">${asset.criticalCount}</td>""" +
                """<td style="$cell text-align: center;">${asset.highCount}</td>""" +
                """<td style="$cell text-align: center;">${asset.oldestDays}d</td>""" +
                """<td style="$cell">$reasons</td></tr>"""
        }
        val escaped = templateRenderer.render(templateRenderer.readHtml(TEMPLATE), values(recipient, thresholdDays), escape = true)
        return templateRenderer.render(escaped, mapOf("assetTableHtml" to rows), escape = false)
    }

    /** Plain-text part: values stay unescaped so readers do not see entities. */
    internal fun renderText(recipient: OverdueRecipient, thresholdDays: Int): String {
        val rows = recipient.assets.joinToString("\n") { asset ->
            "- ${asset.assetName} (AWS ${asset.awsAccountId ?: "-"}): ${asset.overdueCount} overdue " +
                "(critical ${asset.criticalCount}, high ${asset.highCount}), oldest ${asset.oldestDays}d\n" +
                asset.reasons.joinToString("\n") { "    via ${describe(it)}" }
        }
        return templateRenderer.render(
            templateRenderer.readText(TEMPLATE), values(recipient, thresholdDays) + ("assetTableText" to rows), escape = false
        )
    }

    private fun values(recipient: OverdueRecipient, thresholdDays: Int) = mapOf(
        "userEmail" to recipient.email,
        "assetCount" to recipient.assets.size.toString(),
        "totalOverdue" to recipient.assets.sumOf { it.overdueCount }.toString(),
        "totalCritical" to recipient.assets.sumOf { it.criticalCount }.toString(),
        "totalHigh" to recipient.assets.sumOf { it.highCount }.toString(),
        "thresholdDays" to thresholdDays.toString(),
        "dashboardUrl" to "${appConfig.backend.baseUrl.trimEnd('/')}/vulnerabilities/current"
    )

    private fun describe(reason: com.secman.dto.OverdueMatchReason): String =
        if (reason.awsAccountId != null) "workgroup ${reason.workgroupName} (AWS account ${reason.awsAccountId})"
        else "workgroup ${reason.workgroupName} (assigned asset)"

    private fun emptyRun(days: Int, dryRun: Boolean, email: String, userExists: Boolean) = OverdueNotificationRunResult(
        status = if (dryRun) "DRY_RUN" else "SUCCESS", dryRun = dryRun, thresholdDays = days,
        notificationUser = email, notificationUserExists = userExists,
        recipientsResolved = 0, emailsSent = 0, emailsFailed = 0, skipped = 0, recipients = emptyList()
    )

    /** Once per UTC calendar day: a second run the same day skips everyone already mailed. */
    private fun sentToday(): Set<String> {
        val startOfDay = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)
        return notificationLogRepository.findSentRecipientEmailsSince(NotificationType.OVERDUE_WORKGROUP_DIGEST, startOfDay)
            .map { WorkgroupOverdueRecipientQuery.normalizeEmail(it) }
            .toSet()
    }

    /** Same overdue cutoff and observation window as `send-notification-users` uses for findings age. */
    internal fun window(thresholdDays: Int): WorkgroupOverdueRecipientQuery.Window {
        val seen = NotificationAssetSeenWindow.current(clock)
        val localNow = seen.endUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        return WorkgroupOverdueRecipientQuery.Window(
            threshold = localNow.minusDays(thresholdDays.toLong()),
            seenStartUtc = seen.startUtc,
            seenEndUtc = seen.endUtc,
            today = localNow
        )
    }
}
