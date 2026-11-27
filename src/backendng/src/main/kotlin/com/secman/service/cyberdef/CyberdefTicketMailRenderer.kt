package com.secman.service.cyberdef

import com.secman.domain.CyberdefTicket
import com.secman.domain.CyberdefTicketEvent
import com.secman.service.EmailTemplateRenderer
import jakarta.inject.Singleton
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Turns tickets into mail text. Pure apart from reading templates, so the mailer's claim and
 * send loop stays short and the wording is testable on its own.
 *
 * By default the mail names the ticket, account, severity, title and due date and links to
 * SecMan; description and comments stay behind the login unless the operator sets
 * `secman.cyberdef.mail.include-summary=true`.
 */
@Singleton
open class CyberdefTicketMailRenderer(
    private val templates: EmailTemplateRenderer,
    private val settings: CyberdefTicketSettings
) {
    /** Subject plus the two bodies of one mail. */
    data class Mail(val subject: String, val text: String, val html: String)

    /** The mail for one outbox row of [kind]; [event] names the status change for updates. */
    open fun single(kind: String, ticket: CyberdefTicket, event: CyberdefTicketEvent?): Mail {
        val template = templates.requireAllowed(if (kind == CyberdefTicketWriter.KIND_CREATED) TEMPLATE_CREATED else TEMPLATE_UPDATE)
        val values = values(ticket, event?.toStatus?.name ?: ticket.status.name, event?.actorUsername.orEmpty(), event?.comment.orEmpty())
        fun prepare(raw: String) = templates.renderConditionalBlock(
            templates.renderConditionalBlock(raw, "ifSummary", settings.mailIncludesSummary), "ifDue", ticket.dueAt != null)
        val title = CyberdefTicketInput.cleanSingleLine(ticket.title).take(SUBJECT_TITLE_MAX)
        val prefix = if (kind == CyberdefTicketWriter.KIND_CREATED) "[${ticket.severity}] New security ticket" else "Security ticket update"
        return Mail(
            subject = "$prefix ${ticket.ticketKey} for AWS account ${ticket.awsAccountId}: $title",
            text = templates.render(prepare(templates.readText(template)), values, escape = false),
            html = templates.render(prepare(templates.readHtml(template)), values, escape = true)
        )
    }

    /**
     * One mail listing several new tickets of the same submission, most severe first. The list
     * is built here with every field escaped, then inserted after the scalar placeholders are
     * filled, so no ticket text can introduce a placeholder or markup.
     */
    open fun digest(tickets: List<CyberdefTicket>): Mail {
        val template = templates.requireAllowed(TEMPLATE_DIGEST)
        val ordered = tickets.sortedWith(compareBy<CyberdefTicket> { it.severity.ordinal }.thenBy { it.id })
        val accounts = ordered.map { it.awsAccountId }.distinct()
        val values = linkedMapOf(
            "count" to ordered.size.toString(),
            "accountCount" to accounts.size.toString(),
            "ticketsUrl" to settings.ticketListUrl()
        )
        val textList = ordered.joinToString("\n") { t ->
            "  - ${t.ticketKey} [${t.severity}] ${CyberdefTicketInput.cleanSingleLine(t.title).take(SUBJECT_TITLE_MAX)}\n" +
                "    ${label(t)}${t.dueAt?.let { ", due " + DUE_FORMAT.format(it) }.orEmpty()}\n    ${settings.ticketUrl(t.id!!)}"
        }
        val htmlList = ordered.joinToString("\n") { t ->
            "<li style=\"margin-bottom: 8px;\"><a href=\"${templates.escapeHtml(settings.ticketUrl(t.id!!))}\" style=\"color: #1f4e79;\">" +
                "${templates.escapeHtml(t.ticketKey.orEmpty())}</a> <strong>[${t.severity}]</strong> ${templates.escapeHtml(t.title)}" +
                "<br><span style=\"color: #555;\">${templates.escapeHtml(label(t))}" +
                "${t.dueAt?.let { " &middot; due " + DUE_FORMAT.format(it) }.orEmpty()}</span></li>"
        }
        val worst = ordered.first().severity
        val scope = if (accounts.size == 1) "AWS account ${accounts.single()}" else "${accounts.size} of your AWS accounts"
        return Mail(
            subject = "[$worst] ${ordered.size} new security tickets for $scope",
            text = templates.render(templates.readText(template), values, escape = false).replace("{ticketList}", textList),
            html = templates.render(templates.readHtml(template), values, escape = true).replace("{ticketList}", htmlList)
        )
    }

    private fun label(ticket: CyberdefTicket): String =
        ticket.awsAccountName?.let { "$it (${ticket.awsAccountId})" } ?: ticket.awsAccountId

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
            "awsAccountLabel" to label(ticket),
            "severity" to ticket.severity.name,
            "status" to status.replace('_', ' '),
            "dueDate" to (ticket.dueAt?.let { DUE_FORMAT.format(it) } ?: ""),
            "actor" to actor,
            "comment" to (if (settings.mailIncludesSummary) comment else ""),
            "summary" to (if (settings.mailIncludesSummary) ticket.description.take(SUMMARY_MAX) else ""),
            "title" to ticket.title
        )

    companion object {
        const val TEMPLATE_CREATED = "cyberdef-ticket-created"
        const val TEMPLATE_UPDATE = "cyberdef-ticket-update"
        const val TEMPLATE_DIGEST = "cyberdef-ticket-digest"
        private const val SUMMARY_MAX = 2_000
        private const val SUBJECT_TITLE_MAX = 120
        private val DUE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}
