package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind
import com.secman.domain.CyberdefTicketStatus
import com.secman.dto.CyberdefBatchResultDto
import com.secman.dto.CyberdefCreateResultDto
import com.secman.dto.CyberdefResendResultDto
import com.secman.dto.CyberdefTicketDetailDto
import com.secman.repository.CyberdefTicketRepository
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.FORBIDDEN
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.RATE_LIMITED
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.UNROUTABLE_ACCOUNT
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.VALIDATION_ERROR
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.VERSION_CONFLICT
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.defendersOnly
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.notFound
import com.secman.util.EmailAddressValidator
import io.micronaut.scheduling.TaskExecutors
import jakarta.inject.Named
import jakarta.inject.Singleton
import jakarta.persistence.OptimisticLockException
import org.hibernate.StaleObjectStateException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutorService

/**
 * Write side of the account-ticket feature: authorises, validates, delegates the database
 * work to [CyberdefTicketWriter] (one transaction per call) and hands queued mail to
 * [CyberdefTicketMailer] on the IO pool once that transaction has committed.
 *
 * Not transactional itself on purpose: a batch commits ticket by ticket, so one bad item
 * cannot roll back the others, and SMTP never runs while a transaction is open.
 */
@Singleton
open class CyberdefTicketService(
    private val tickets: CyberdefTicketRepository,
    private val writer: CyberdefTicketWriter,
    private val mailer: CyberdefTicketMailer,
    private val resolver: CyberdefRecipientResolver,
    private val access: CyberdefTicketAccess,
    private val queries: CyberdefTicketQueryService,
    private val settings: CyberdefTicketSettings,
    @Named(TaskExecutors.IO) private val executor: ExecutorService
) {
    private val log = LoggerFactory.getLogger(javaClass)

/** Submits 1-100 tickets, each in its own transaction, and reports every item. */
    open fun createBatch(actor: CyberdefTicketActor, requests: List<CyberdefTicketCreateRequest>): CyberdefTicketResult<CyberdefBatchResultDto> {
        if (!actor.isDefender) return defendersOnly
        if (requests.isEmpty() || requests.size > CyberdefTicketInput.BATCH_MAX) {
            return CyberdefTicketResult.Failed(VALIDATION_ERROR, "tickets must contain 1-${CyberdefTicketInput.BATCH_MAX} items")
        }
        val results = requests.mapIndexed { index, request -> createOne(actor, request, index) }
        return CyberdefTicketResult.Ok(
            CyberdefBatchResultDto(
                created = results.count { it.outcome == OUTCOME_CREATED },
                duplicates = results.count { it.outcome == OUTCOME_DUPLICATE },
                rejected = results.count { it.outcome == OUTCOME_REJECTED },
                results = results
            )
        )
    }

/** Submits one ticket; a rejection is reported in the result, not as a failure. */
    open fun create(actor: CyberdefTicketActor, request: CyberdefTicketCreateRequest): CyberdefTicketResult<CyberdefCreateResultDto> {
        if (!actor.isDefender) return defendersOnly
        return CyberdefTicketResult.Ok(createOne(actor, request, 0))
    }

    private fun createOne(actor: CyberdefTicketActor, request: CyberdefTicketCreateRequest, index: Int): CyberdefCreateResultDto {
        val now = Instant.now()
        val input = when (val outcome = CyberdefTicketInput.validate(request, now)) {
            is ValidationOutcome.Invalid -> return rejected(index, VALIDATION_ERROR, outcome.errors)
            is ValidationOutcome.Valid -> outcome.ticket
        }
        duplicateOf(actor, input, index)?.let { return it }
        if (tickets.countByCreatedByUserIdAndCreatedAtAfter(actor.userId, now.minus(Duration.ofHours(1))) >= settings.maxTicketsPerHour) {
            return rejected(index, RATE_LIMITED, listOf("at most ${settings.maxTicketsPerHour} tickets per hour"))
        }
        val dueAt = when (val due = dueDate(input, now)) {
            is DueDate.Invalid -> return rejected(index, VALIDATION_ERROR, listOf(due.message))
            is DueDate.Valid -> due.value
        }
        val routing = resolver.resolve(input.awsAccountId)
        if (routing.recipients.isEmpty() && !input.allowUnrouted) {
            return rejected(index, UNROUTABLE_ACCOUNT, listOf(
                "nobody is allocated to AWS account ${input.awsAccountId}; map an owner first or resend with allowUnrouted=true"
            ))
        }
        if (routing.truncated) {
            log.warn("Cyberdef ticket recipients capped account={} cap={}", input.awsAccountId, settings.maxRecipients)
        }
        val written = try {
            writer.create(actor, input, routing, dueAt)
        } catch (e: RuntimeException) {
            // A concurrent submission with the same externalReference loses the unique key race.
            duplicateOf(actor, input, index)?.let { return it }
            log.error("Cyberdef ticket create failed actor={} account={} exceptionType={}",
                actor.userId, input.awsAccountId, e.javaClass.simpleName, e)
            return rejected(index, CyberdefTicketResult.INTERNAL_ERROR, listOf("ticket could not be stored"))
        }
        dispatchAsync(written.notificationIds)
        val ticket = written.ticket
        log.info("AUDIT operation=CYBERDEF_TICKET_CREATE actorId={} ticketId={} account={} severity={} recipients={} outcome=CREATED",
            actor.userId, ticket.id, ticket.awsAccountId, ticket.severity, ticket.recipientCount)
        return CyberdefCreateResultDto(
            index = index, outcome = OUTCOME_CREATED, ticketId = ticket.id, ticketKey = ticket.ticketKey,
            status = ticket.status.name, recipientCount = ticket.recipientCount
        )
    }

    open fun transition(
        actor: CyberdefTicketActor,
        ticketId: Long,
        toStatus: String?,
        rawComment: String?,
        expectedVersion: Long?,
        onlyAs: CyberdefActorKind? = null
    ): CyberdefTicketResult<CyberdefTicketDetailDto> {
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
        // cannot resolve their own ticket through the defender tool.
        val kinds = if (onlyAs == null) held else held intersect setOf(onlyAs)

        val outcome = try {
            writer.transition(actor, ticketId, kinds, target, comment, expectedVersion)
        } catch (e: RuntimeException) {
            if (!isOptimisticLock(e)) throw e
            return CyberdefTicketResult.Failed(VERSION_CONFLICT, "The ticket was changed by someone else; reload it and try again")
        }
        return when (outcome) {
            is CyberdefTicketWriter.TransitionOutcome.Missing -> notFound
            is CyberdefTicketWriter.TransitionOutcome.Conflict -> CyberdefTicketResult.Failed(VERSION_CONFLICT,
                "The ticket changed since you loaded it (now ${outcome.currentStatus}, version ${outcome.currentVersion}); reload it and try again")
            is CyberdefTicketWriter.TransitionOutcome.Denied -> {
                log.info("AUDIT operation=CYBERDEF_TICKET_TRANSITION actorId={} ticketId={} from={} to={} outcome=DENIED code={}",
                    actor.userId, ticketId, ticket.status, target, outcome.code)
                CyberdefTicketResult.Failed(outcome.code, outcome.message)
            }
            is CyberdefTicketWriter.TransitionOutcome.Done -> {
                dispatchAsync(outcome.written.notificationIds)
                log.info("AUDIT operation=CYBERDEF_TICKET_TRANSITION actorId={} ticketId={} from={} to={} outcome=SUCCESS",
                    actor.userId, ticketId, ticket.status, target)
                CyberdefTicketResult.Ok(queries.detailOf(actor, outcome.written.ticket))
            }
        }
    }

/** Adds a comment without changing the status. Terminal tickets accept none. */
    open fun comment(actor: CyberdefTicketActor, ticketId: Long, rawComment: String?): CyberdefTicketResult<CyberdefTicketDetailDto> {
        val comment = try {
            CyberdefTicketInput.cleanComment(rawComment)
        } catch (e: IllegalArgumentException) {
            return CyberdefTicketResult.Failed(VALIDATION_ERROR, e.message ?: "invalid comment")
        } ?: return CyberdefTicketResult.Failed(VALIDATION_ERROR, "comment is required")
        val ticket = access.loadVisible(actor, ticketId) ?: return notFound
        val kinds = access.kinds(actor, ticket)
        if (kinds.isEmpty()) return notFound
        if (ticket.status.isTerminal) {
            return CyberdefTicketResult.Failed(FORBIDDEN, "Closed and cancelled tickets accept no further comments")
        }
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

    private fun duplicateOf(actor: CyberdefTicketActor, input: ValidTicket, index: Int): CyberdefCreateResultDto? {
        val reference = input.externalReference ?: return null
        val existing = tickets.findByCreatedByUserIdAndExternalReference(actor.userId, reference).orElse(null) ?: return null
        return CyberdefCreateResultDto(
            index = index, outcome = OUTCOME_DUPLICATE, ticketId = existing.id, ticketKey = existing.ticketKey,
            status = existing.status.name, recipientCount = existing.recipientCount
        )
    }

/** Resolved due date, or why the requested one is not allowed. */
    private sealed class DueDate {
/** Null means the severity has no deadline. */
        data class Valid(val value: Instant?) : DueDate()
/** The requested date exceeds what the SLA allows. */
        data class Invalid(val message: String) : DueDate()
    }

    /** Callers may tighten the severity SLA, never loosen it. */
    private fun dueDate(input: ValidTicket, now: Instant): DueDate {
        val sla = settings.slaFor(input.severity)
        val latest = now.plus(sla ?: MAX_DUE_WITHOUT_SLA)
        val requested = input.dueAt ?: return DueDate.Valid(sla?.let { now.plus(it) })
        if (requested.isAfter(latest)) {
            val window = sla?.let { "the ${input.severity} SLA of ${it.toDays()} days" } ?: "${MAX_DUE_WITHOUT_SLA.toDays()} days"
            return DueDate.Invalid("dueAt may not be later than $window")
        }
        return DueDate.Valid(requested)
    }

    private fun rejected(index: Int, code: String, errors: List<String>) =
        CyberdefCreateResultDto(index = index, outcome = OUTCOME_REJECTED, errorCode = code,
            errors = errors.map { EmailAddressValidator.sanitizeForEcho(it, 300) })

    private fun dispatchAsync(ids: List<Long>) {
        if (ids.isEmpty()) return
        executor.execute {
            try {
                mailer.dispatch(ids)
            } catch (e: Exception) {
                // Rows stay PENDING; CyberdefTicketMailer.recoverStalePending retries them.
                log.warn("Cyberdef ticket mail dispatch failed count={} exceptionType={}", ids.size, e.javaClass.simpleName)
            }
        }
    }

    private fun isOptimisticLock(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is OptimisticLockException || it is StaleObjectStateException }

    companion object {
        const val OUTCOME_CREATED = "CREATED"
        const val OUTCOME_DUPLICATE = "DUPLICATE"
        const val OUTCOME_REJECTED = "REJECTED"
        private val MAX_DUE_WITHOUT_SLA: Duration = Duration.ofDays(365)
    }
}
