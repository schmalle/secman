package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind
import com.secman.domain.CyberdefTicket
import com.secman.domain.CyberdefTicketEvent
import com.secman.domain.CyberdefTicketEventType
import com.secman.domain.CyberdefTicketNotification
import com.secman.domain.CyberdefTicketRecipient
import com.secman.domain.CyberdefTicketResource
import com.secman.domain.CyberdefTicketStatus
import com.secman.repository.CyberdefTicketEventRepository
import com.secman.repository.CyberdefTicketNotificationRepository
import com.secman.repository.CyberdefTicketRecipientRepository
import com.secman.repository.CyberdefTicketRepository
import com.secman.repository.CyberdefTicketResourceRepository
import com.secman.repository.UserRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset

/**
 * Every database write of the feature, one transaction per public method.
 *
 * Mail is only *queued* here (outbox rows in the same transaction). Sending happens in
 * [CyberdefTicketMailer] after commit, so SMTP never runs inside a transaction.
 * Status rules are re-checked against the row as locked by this transaction, so a
 * concurrent change can never be overwritten with a check made on stale data.
 */
@Singleton
open class CyberdefTicketWriter(
    private val tickets: CyberdefTicketRepository,
    private val resources: CyberdefTicketResourceRepository,
    private val events: CyberdefTicketEventRepository,
    private val recipients: CyberdefTicketRecipientRepository,
    private val notifications: CyberdefTicketNotificationRepository,
    private val users: UserRepository,
    private val settings: CyberdefTicketSettings
) {
/** The stored ticket and the outbox rows to send once the transaction committed. */
    data class Written(val ticket: CyberdefTicket, val notificationIds: List<Long>)

/** What a status change did, decided against the row as read in the transaction. */
    sealed class TransitionOutcome {
/** The change was stored. */
        data class Done(val written: Written) : TransitionOutcome()
/** The workflow refused the move. */
        data class Denied(val code: String, val message: String) : TransitionOutcome()
/** The caller saw an older version; nothing was changed. */
        data class Conflict(val currentVersion: Long, val currentStatus: CyberdefTicketStatus) : TransitionOutcome()
/** The ticket no longer exists. */
        object Missing : TransitionOutcome()
    }

/** Stores a new ticket with its resources, creation event, recipients and queued mail. */
    @Transactional
    open fun create(actor: CyberdefTicketActor, input: ValidTicket, routing: CyberdefRouting, dueAt: Instant?): Written {
        val now = Instant.now()
        val ticket = tickets.save(
            CyberdefTicket(
                awsAccountId = input.awsAccountId, awsAccountName = routing.accountName, title = input.title,
                description = input.description, recommendedAction = input.recommendedAction, severity = input.severity,
                cveIds = input.cves.joinToString(",").ifEmpty { null },
                referenceUrls = input.references.joinToString("\n").ifEmpty { null }, dueAt = dueAt,
                externalReference = input.externalReference, sourceSystem = input.sourceSystem,
                createdByUserId = actor.userId, createdByUsername = actor.username,
                recipientCount = routing.recipients.size, createdAt = now, updatedAt = now
            )
        )
        val id = ticket.id!!
        ticket.ticketKey = ticketKey(id, now)
        tickets.update(ticket)

        if (input.resources.isNotEmpty()) {
            resources.saveAll(input.resources.map {
                CyberdefTicketResource(ticketId = id, resourceType = it.type!!, identifier = it.identifier!!, region = it.region)
            })
        }
        val event = events.save(
            CyberdefTicketEvent(
                ticketId = id, actorUserId = actor.userId, actorUsername = actor.username,
                actorKind = CyberdefActorKind.DEFENDER, eventType = CyberdefTicketEventType.CREATED,
                toStatus = CyberdefTicketStatus.NEW, createdAt = now
            )
        )
        if (routing.recipients.isNotEmpty()) {
            recipients.saveAll(routing.recipients.map {
                CyberdefTicketRecipient(ticketId = id, email = it.email, userId = it.userId, source = it.source)
            })
        }
        val queued = queue(id, event.id!!, KIND_CREATED, routing.recipients.map { it.email }, now)
        return Written(ticket, queued)
    }

/** Applies one status change if the workflow and the expected version allow it. */
    @Transactional
    open fun transition(
        actor: CyberdefTicketActor,
        ticketId: Long,
        kinds: Set<CyberdefActorKind>,
        target: CyberdefTicketStatus,
        comment: String?,
        expectedVersion: Long?
    ): TransitionOutcome {
        val ticket = tickets.findById(ticketId).orElse(null) ?: return TransitionOutcome.Missing
        if (expectedVersion != null && expectedVersion != ticket.version) {
            return TransitionOutcome.Conflict(ticket.version, ticket.status)
        }
        val from = ticket.status
        val actedAs = when (val check = CyberdefTicketWorkflow.isAllowed(from, target, kinds, comment)) {
            is CyberdefTicketWorkflow.Check.Denied -> return TransitionOutcome.Denied(check.code, check.message)
            is CyberdefTicketWorkflow.Check.Allowed -> check.actor
        }
        val now = Instant.now()
        ticket.status = target
        ticket.updatedAt = now
        when (target) {
            CyberdefTicketStatus.ACKNOWLEDGED -> ticket.acknowledgedAt = ticket.acknowledgedAt ?: now
            CyberdefTicketStatus.IN_PROGRESS -> ticket.acknowledgedAt = ticket.acknowledgedAt ?: now
            CyberdefTicketStatus.RESOLVED, CyberdefTicketStatus.RISK_ACCEPTED, CyberdefTicketStatus.FALSE_POSITIVE ->
                ticket.resolvedAt = now
            CyberdefTicketStatus.REOPENED -> ticket.resolvedAt = null
            CyberdefTicketStatus.CLOSED, CyberdefTicketStatus.CANCELLED -> ticket.closedAt = now
            else -> Unit
        }
        val saved = tickets.update(ticket)
        val event = events.save(
            CyberdefTicketEvent(
                ticketId = ticketId, actorUserId = actor.userId, actorUsername = actor.username, actorKind = actedAs,
                eventType = CyberdefTicketEventType.STATUS_CHANGED, fromStatus = from, toStatus = target,
                comment = comment, createdAt = now
            )
        )
        val kind = if (actedAs == CyberdefActorKind.DEFENDER) KIND_DEFENDER_UPDATE else KIND_STATUS_CHANGED
        val queued = queue(ticketId, event.id!!, kind, mailRecipients(ticket, actedAs, from, target), now)
        return TransitionOutcome.Done(Written(saved, queued))
    }

    /** Returns null when the ticket no longer exists. */
    @Transactional
    open fun comment(actor: CyberdefTicketActor, ticketId: Long, kind: CyberdefActorKind, comment: String): CyberdefTicket? {
        val ticket = tickets.findById(ticketId).orElse(null) ?: return null
        val now = Instant.now()
        ticket.updatedAt = now
        val saved = tickets.update(ticket)
        events.save(
            CyberdefTicketEvent(
                ticketId = ticketId, actorUserId = actor.userId, actorUsername = actor.username, actorKind = kind,
                eventType = CyberdefTicketEventType.COMMENT, comment = comment, createdAt = now
            )
        )
        return saved
    }

    /**
     * Mail on a status change goes where the next move is expected: the account team when
     * Cyber Defence reopens, answers or cancels; the submitter (opt-in) when the owners act.
     * CLOSED is the end of the conversation and is not mailed.
     */
    private fun mailRecipients(
        ticket: CyberdefTicket,
        actedAs: CyberdefActorKind,
        from: CyberdefTicketStatus,
        target: CyberdefTicketStatus
    ): List<String> = when {
        actedAs == CyberdefActorKind.DEFENDER && (target == CyberdefTicketStatus.REOPENED ||
            target == CyberdefTicketStatus.CANCELLED ||
            (from == CyberdefTicketStatus.NEEDS_INFO && target == CyberdefTicketStatus.IN_PROGRESS)) ->
            recipients.findByTicketIdOrderByEmailAsc(ticket.id!!).map { it.email }
        actedAs == CyberdefActorKind.OWNER && settings.notifySubmitter ->
            users.findById(ticket.createdByUserId).map { listOf(it.email) }.orElse(emptyList())
        else -> emptyList()
    }

    private fun queue(ticketId: Long, eventId: Long, kind: String, emails: List<String>, now: Instant): List<Long> {
        if (emails.isEmpty()) return emptyList()
        val rows = emails.distinct().map { email ->
            CyberdefTicketNotification(
                ticketId = ticketId, recipientEmail = email, kind = kind, eventId = eventId,
                eventKey = eventKey(ticketId, eventId, kind, email), createdAt = now, updatedAt = now
            )
        }
        return notifications.saveAll(rows).mapNotNull { it.id }
    }

    companion object {
        const val KIND_CREATED = "CREATED"
        const val KIND_STATUS_CHANGED = "STATUS_CHANGED"
        const val KIND_DEFENDER_UPDATE = "DEFENDER_UPDATE"

        fun ticketKey(id: Long, createdAt: Instant): String =
            "CD-%d-%06d".format(createdAt.atZone(ZoneOffset.UTC).year, id)

        fun eventKey(ticketId: Long, eventId: Long, kind: String, email: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$ticketId:$eventId:$kind:$email".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
