package com.secman.service.cyberdef

import com.secman.domain.CyberdefTicket
import com.secman.domain.CyberdefTicketNotification
import com.secman.repository.CyberdefTicketEventRepository
import com.secman.repository.CyberdefTicketNotificationRepository
import com.secman.repository.CyberdefTicketRepository
import com.secman.service.EmailService
import com.secman.service.EmailTemplateRenderer
import io.micronaut.data.model.Pageable
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Sends queued ticket mail. Never called inside a transaction: each row is claimed with a
 * single conditional UPDATE, sent, then finished with another, so two dispatchers (the
 * request thread and the recovery job) can never send the same row twice.
 *
 * By default the mail names the ticket, account, severity, title and due date and links
 * to SecMan; description and resources stay behind the login unless the operator sets
 * `secman.cyberdef.mail.include-summary=true`.
 */
@Singleton
open class CyberdefTicketMailer(
    private val notifications: CyberdefTicketNotificationRepository,
    private val tickets: CyberdefTicketRepository,
    private val events: CyberdefTicketEventRepository,
    private val email: EmailService,
    private val templates: EmailTemplateRenderer,
    private val settings: CyberdefTicketSettings
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** How many rows a dispatch run actually sent or failed. */
    data class Tally(val attempted: Int, val sent: Int, val failed: Int)

    open fun dispatch(notificationIds: Collection<Long>): Tally =
        send(notificationIds, CyberdefTicketNotification.STATUS_PENDING)

    /** Retries only rows that failed cleanly. UNCERTAIN rows may already have been delivered. */
    open fun resendFailed(ticketId: Long): Tally =
        send(notifications.findByTicketIdAndStatus(ticketId, CyberdefTicketNotification.STATUS_FAILED).mapNotNull { it.id },
            CyberdefTicketNotification.STATUS_FAILED)

    /** Picks up rows a crash left between commit and dispatch. */
    @Scheduled(fixedDelay = "5m", initialDelay = "3m")
    open fun recoverStalePending() {
        val stale = notifications.findStalePending(Instant.now().minus(STALE_AFTER), Pageable.from(0, RECOVERY_BATCH))
        if (stale.isNotEmpty()) {
            val tally = dispatch(stale.mapNotNull { it.id })
            log.info("Cyberdef ticket mail recovery attempted={} sent={} failed={}", tally.attempted, tally.sent, tally.failed)
        }
    }

    private fun send(ids: Collection<Long>, expected: String): Tally {
        var attempted = 0
        var sent = 0
        var failed = 0
        val ticketCache = mutableMapOf<Long, CyberdefTicket?>()
        for (id in ids) {
            if (notifications.claim(id, expected, Instant.now()) != 1) continue
            val row = notifications.findById(id).orElse(null) ?: continue
            attempted++
            val ticket = ticketCache.getOrPut(row.ticketId) { tickets.findById(row.ticketId).orElse(null) }
            val result = if (ticket == null) {
                EmailService.TrackedDelivery("FAILED", "TICKET_MISSING")
            } else try {
                deliver(row, ticket)
            } catch (e: Exception) {
                log.warn("Cyberdef ticket mail failed notificationId={} exceptionType={}", id, e.javaClass.simpleName)
                EmailService.TrackedDelivery("PENDING", "DELIVERY_UNCERTAIN")
            }
            val status = when (result.status) {
                "SENT" -> CyberdefTicketNotification.STATUS_SENT
                "FAILED" -> CyberdefTicketNotification.STATUS_FAILED
                else -> CyberdefTicketNotification.STATUS_UNCERTAIN
            }
            val now = Instant.now()
            notifications.finish(id, status, result.errorCode?.take(64), now, if (status == CyberdefTicketNotification.STATUS_SENT) now else null)
            if (status == CyberdefTicketNotification.STATUS_SENT) sent++ else failed++
            log.info("AUDIT operation=CYBERDEF_TICKET_MAIL notificationId={} ticketId={} kind={} outcome={}",
                id, row.ticketId, row.kind, status)
        }
        return Tally(attempted, sent, failed)
    }

    private fun deliver(row: CyberdefTicketNotification, ticket: CyberdefTicket): EmailService.TrackedDelivery {
        val template = templates.requireAllowed(
            if (row.kind == CyberdefTicketWriter.KIND_CREATED) TEMPLATE_CREATED else TEMPLATE_UPDATE
        )
        val event = row.eventId?.let { events.findById(it).orElse(null) }
        val values = values(ticket, event?.toStatus?.name ?: ticket.status.name, event?.actorUsername.orEmpty(),
            event?.comment.orEmpty())
        fun prepare(raw: String) = templates.renderConditionalBlock(
            templates.renderConditionalBlock(raw, "ifSummary", settings.mailIncludesSummary), "ifDue", ticket.dueAt != null)
        val subject = subject(row.kind, ticket)
        return email.sendTrackedEmailWithInlineImages(
            row.recipientEmail, subject,
            templates.render(prepare(templates.readText(template)), values, escape = false),
            templates.render(prepare(templates.readHtml(template)), values, escape = true),
            templates.loadLogoInlineImage()
        )
    }

    /**
     * Values filled into `{placeholder}`s. The renderer replaces in map order, so values that
     * come from submitters (title, comment, summary) go last: a title containing
     * `{ticketUrl}` then stays literal instead of turning into a link.
     */
    private fun values(ticket: CyberdefTicket, status: String, actor: String, comment: String): Map<String, String> =
        linkedMapOf(
            "ticketKey" to ticket.ticketKey.orEmpty(),
            "ticketUrl" to settings.ticketUrl(ticket.id!!),
            "awsAccountId" to ticket.awsAccountId,
            "awsAccountLabel" to (ticket.awsAccountName?.let { "$it (${ticket.awsAccountId})" } ?: ticket.awsAccountId),
            "severity" to ticket.severity.name,
            "status" to status.replace('_', ' '),
            "dueDate" to (ticket.dueAt?.let { DUE_FORMAT.format(it) } ?: ""),
            "actor" to actor,
            "comment" to (if (settings.mailIncludesSummary) comment else ""),
            "summary" to (if (settings.mailIncludesSummary) ticket.description.take(SUMMARY_MAX) else ""),
            "title" to ticket.title
        )

    private fun subject(kind: String, ticket: CyberdefTicket): String {
        val title = CyberdefTicketInput.cleanSingleLine(ticket.title).take(SUBJECT_TITLE_MAX)
        val prefix = if (kind == CyberdefTicketWriter.KIND_CREATED) "[${ticket.severity}] New security ticket"
            else "Security ticket update"
        return "$prefix ${ticket.ticketKey} for AWS account ${ticket.awsAccountId}: $title"
    }

    companion object {
        const val TEMPLATE_CREATED = "cyberdef-ticket-created"
        const val TEMPLATE_UPDATE = "cyberdef-ticket-update"
        private val STALE_AFTER: Duration = Duration.ofMinutes(2)
        private const val RECOVERY_BATCH = 100
        private const val SUMMARY_MAX = 2_000
        private const val SUBJECT_TITLE_MAX = 120
        private val DUE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}
