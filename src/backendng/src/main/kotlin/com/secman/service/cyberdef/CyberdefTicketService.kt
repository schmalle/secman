package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind
import com.secman.domain.CyberdefTicket
import com.secman.domain.CyberdefTicketStatus
import com.secman.dto.CyberdefResendResultDto
import com.secman.dto.CyberdefTicketDetailDto
import com.secman.dto.CyberdefTransitionPreviewDto
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.VALIDATION_ERROR
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.VERSION_CONFLICT
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.defendersOnly
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.notFound
import jakarta.inject.Singleton
import jakarta.persistence.OptimisticLockException
import org.hibernate.StaleObjectStateException
import org.slf4j.LoggerFactory

/**
 * Changes to existing tickets: status changes (and their dry runs), comments and mail
 * resends. Creation lives in [CyberdefTicketSubmissionService].
 *
 * Not transactional itself on purpose: the database work runs in [CyberdefTicketWriter]'s
 * transaction and queued mail is sent by [CyberdefTicketMailer] after it committed, so SMTP
 * never runs while a transaction is open.
 */
@Singleton
open class CyberdefTicketService(
    private val writer: CyberdefTicketWriter,
    private val mailer: CyberdefTicketMailer,
    private val access: CyberdefTicketAccess,
    private val queries: CyberdefTicketQueryService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** A validated status-change request against a ticket the caller can see. */
    private data class PreparedTransition(
        val ticket: CyberdefTicket,
        val target: CyberdefTicketStatus,
        val comment: String?,
        val kinds: Set<CyberdefActorKind>
    )

    /** Shared front half of [transition] and [previewTransition]: parse, load, scope. */
    private fun prepare(
        actor: CyberdefTicketActor,
        ticketId: Long,
        toStatus: String?,
        rawComment: String?,
        onlyAs: CyberdefActorKind?
    ): CyberdefTicketResult<PreparedTransition> {
        val target = CyberdefTicketStatus.entries.firstOrNull { it.name == toStatus?.trim()?.uppercase() }
            ?: return CyberdefTicketResult.Failed(VALIDATION_ERROR, "toStatus must be one of ${CyberdefTicketStatus.entries.joinToString()}")
        val comment = try {
            CyberdefTicketInput.cleanComment(rawComment)
        } catch (e: IllegalArgumentException) {
            return CyberdefTicketResult.Failed(VALIDATION_ERROR, e.message ?: "invalid comment")
        }
        val ticket = access.loadVisible(actor, ticketId) ?: return notFound
        val held = access.kinds(actor, ticket)
        if (held.isEmpty()) return notFound
        // MCP tools act for one side only, so a CYBERDEF user who also owns the account
        // cannot use the defender tool to close their own ticket as its owner.
        val kinds = if (onlyAs == null) held else held intersect setOf(onlyAs)
        return CyberdefTicketResult.Ok(PreparedTransition(ticket, target, comment, kinds))
    }

    /** Changes a ticket's status if the workflow and [expectedVersion] allow it. */
    open fun transition(
        actor: CyberdefTicketActor,
        ticketId: Long,
        toStatus: String?,
        rawComment: String?,
        expectedVersion: Long?,
        onlyAs: CyberdefActorKind? = null
    ): CyberdefTicketResult<CyberdefTicketDetailDto> {
        val move = when (val prepared = prepare(actor, ticketId, toStatus, rawComment, onlyAs)) {
            is CyberdefTicketResult.Failed -> return prepared
            is CyberdefTicketResult.Ok -> prepared.value
        }
        val outcome = try {
            writer.transition(actor, ticketId, move.kinds, move.target, move.comment, expectedVersion)
        } catch (e: RuntimeException) {
            if (!isOptimisticLock(e)) throw e
            return CyberdefTicketResult.Failed(VERSION_CONFLICT, "The ticket was changed by someone else; reload it and try again")
        }
        val from = move.ticket.status
        return when (outcome) {
            is CyberdefTicketWriter.TransitionOutcome.Missing -> notFound
            is CyberdefTicketWriter.TransitionOutcome.Conflict -> conflict(outcome.currentStatus, outcome.currentVersion)
            is CyberdefTicketWriter.TransitionOutcome.Denied -> {
                log.info("AUDIT operation=CYBERDEF_TICKET_TRANSITION actorId={} ticketId={} from={} to={} outcome=DENIED code={}",
                    actor.userId, ticketId, from, move.target, outcome.code)
                CyberdefTicketResult.Failed(outcome.code, outcome.message)
            }
            is CyberdefTicketWriter.TransitionOutcome.Done -> {
                mailer.dispatchLater(outcome.written.notificationIds)
                log.info("AUDIT operation=CYBERDEF_TICKET_TRANSITION actorId={} ticketId={} from={} to={} outcome=SUCCESS",
                    actor.userId, ticketId, from, move.target)
                CyberdefTicketResult.Ok(queries.detailOf(actor, outcome.written.ticket))
            }
        }
    }

    /** Dry run of [transition]: the same checks, plus who would be mailed. Writes and sends nothing. */
    open fun previewTransition(
        actor: CyberdefTicketActor,
        ticketId: Long,
        toStatus: String?,
        rawComment: String?,
        expectedVersion: Long?,
        onlyAs: CyberdefActorKind? = null
    ): CyberdefTicketResult<CyberdefTransitionPreviewDto> {
        val move = when (val prepared = prepare(actor, ticketId, toStatus, rawComment, onlyAs)) {
            is CyberdefTicketResult.Failed -> return prepared
            is CyberdefTicketResult.Ok -> prepared.value
        }
        val ticket = move.ticket
        if (expectedVersion != null && expectedVersion != ticket.version) return conflict(ticket.status, ticket.version)
        val side = when (val check = CyberdefTicketWorkflow.isAllowed(ticket.status, move.target, move.kinds, move.comment)) {
            is CyberdefTicketWorkflow.Check.Denied -> return CyberdefTicketResult.Failed(check.code, check.message)
            is CyberdefTicketWorkflow.Check.Allowed -> check.actor
        }
        return CyberdefTicketResult.Ok(
            CyberdefTransitionPreviewDto(
                ticketId = ticketId, ticketKey = ticket.ticketKey, fromStatus = ticket.status.name, toStatus = move.target.name,
                currentVersion = ticket.version, actingAs = side.name, wouldNotify = writer.notifyTargets(ticket, side)
            )
        )
    }

    private fun conflict(status: CyberdefTicketStatus, version: Long) = CyberdefTicketResult.Failed(VERSION_CONFLICT,
        "The ticket changed since you loaded it (now $status, version $version); reload it and try again")

    /** Adds a comment without changing the status. Open and closed tickets both accept one. */
    open fun comment(actor: CyberdefTicketActor, ticketId: Long, rawComment: String?): CyberdefTicketResult<CyberdefTicketDetailDto> {
        val comment = try {
            CyberdefTicketInput.cleanComment(rawComment)
        } catch (e: IllegalArgumentException) {
            return CyberdefTicketResult.Failed(VALIDATION_ERROR, e.message ?: "invalid comment")
        } ?: return CyberdefTicketResult.Failed(VALIDATION_ERROR, "comment is required")
        val ticket = access.loadVisible(actor, ticketId) ?: return notFound
        val kinds = access.kinds(actor, ticket)
        if (kinds.isEmpty()) return notFound
        // Speak as a defender when one can; ADMIN holds both sides.
        val kind = if (CyberdefActorKind.DEFENDER in kinds) CyberdefActorKind.DEFENDER else CyberdefActorKind.OWNER
        val saved = writer.comment(actor, ticketId, kind, comment) ?: return notFound
        log.info("AUDIT operation=CYBERDEF_TICKET_COMMENT actorId={} ticketId={} actorKind={} outcome=SUCCESS",
            actor.userId, ticketId, kind)
        return CyberdefTicketResult.Ok(queries.detailOf(actor, saved))
    }

    /** Retries the ticket's cleanly failed mail now; Cyber Defence only. */
    open fun resendFailed(actor: CyberdefTicketActor, ticketId: Long): CyberdefTicketResult<CyberdefResendResultDto> {
        if (!actor.isDefender) return defendersOnly
        access.loadVisible(actor, ticketId) ?: return notFound
        val tally = mailer.resendFailed(ticketId)
        log.info("AUDIT operation=CYBERDEF_TICKET_RESEND actorId={} ticketId={} attempted={} sent={} outcome=DONE",
            actor.userId, ticketId, tally.attempted, tally.sent)
        return CyberdefTicketResult.Ok(CyberdefResendResultDto(tally.attempted, tally.sent, tally.failed))
    }

    private fun isOptimisticLock(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is OptimisticLockException || it is StaleObjectStateException }
}
