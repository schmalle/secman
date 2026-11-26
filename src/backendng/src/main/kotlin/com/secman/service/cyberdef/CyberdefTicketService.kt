package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind
import com.secman.domain.CyberdefTicket
import com.secman.domain.CyberdefTicketStatus
import com.secman.dto.CyberdefBatchResultDto
import com.secman.dto.CyberdefCreateResultDto
import com.secman.dto.CyberdefResendResultDto
import com.secman.dto.CyberdefTicketDetailDto
import com.secman.dto.CyberdefTransitionPreviewDto
import com.secman.repository.CyberdefTicketRepository
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

    /**
     * Submits 1-100 tickets, each in its own transaction, and reports every item. A batch
     * [dryRun] or [recipients] applies to every item that does not set its own.
     */
    open fun createBatch(
        actor: CyberdefTicketActor,
        requests: List<CyberdefTicketCreateRequest>,
        dryRun: Boolean = false,
        recipients: List<String>? = null
    ): CyberdefTicketResult<CyberdefBatchResultDto> {
        if (!actor.isDefender) return defendersOnly
        if (requests.isEmpty() || requests.size > CyberdefTicketInput.BATCH_MAX) {
            return CyberdefTicketResult.Failed(VALIDATION_ERROR, "tickets must contain 1-${CyberdefTicketInput.BATCH_MAX} items")
        }
        val results = requests.mapIndexed { index, request ->
            createOne(actor, request.copy(dryRun = request.dryRun ?: dryRun, recipients = request.recipients ?: recipients), index)
        }
        return CyberdefTicketResult.Ok(
            CyberdefBatchResultDto(
                dryRun = results.all { it.dryRun },
                created = results.count { it.outcome == OUTCOME_CREATED || it.outcome == OUTCOME_WOULD_CREATE },
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

    /**
     * Validation, idempotency, rate limit, due date and routing are identical for a dry run
     * and a real submission; a dry run simply stops before the first write.
     */
    private fun createOne(actor: CyberdefTicketActor, request: CyberdefTicketCreateRequest, index: Int): CyberdefCreateResultDto {
        val now = Instant.now()
        val input = when (val outcome = CyberdefTicketInput.validate(request, now)) {
            is ValidationOutcome.Invalid -> return rejected(index, VALIDATION_ERROR, outcome.errors, request.dryRun == true)
            is ValidationOutcome.Valid -> outcome.ticket
        }
        duplicateOf(actor, input, index)?.let { return it }
        if (tickets.countByCreatedByUserIdAndCreatedAtAfter(actor.userId, now.minus(Duration.ofHours(1))) >= settings.maxTicketsPerHour) {
            return rejected(index, RATE_LIMITED, listOf("at most ${settings.maxTicketsPerHour} tickets per hour"), input.dryRun)
        }
        val dueAt = when (val due = dueDate(input, now)) {
            is DueDate.Invalid -> return rejected(index, VALIDATION_ERROR, listOf(due.message), input.dryRun)
            is DueDate.Valid -> due.value
        }
        val routing = when (val routed = route(input)) {
            is Routed.Refused -> return rejected(index, routed.code, listOf(routed.message), input.dryRun)
            is Routed.To -> routed.routing
        }
        val overridden = input.recipientOverride.isNotEmpty()
        val emails = routing.recipients.map { it.email }
        if (input.dryRun) {
            log.info("AUDIT operation=CYBERDEF_TICKET_CREATE actorId={} account={} recipients={} overridden={} outcome=DRY_RUN",
                actor.userId, input.awsAccountId, emails.size, overridden)
            return CyberdefCreateResultDto(index = index, outcome = OUTCOME_WOULD_CREATE, status = CyberdefTicketStatus.OPEN.name,
                recipientCount = emails.size, recipients = emails, recipientsOverridden = overridden, dueAt = dueAt, dryRun = true)
        }
        val written = try {
            writer.create(actor, input, routing, dueAt)
        } catch (e: RuntimeException) {
            // A concurrent submission with the same externalReference loses the unique key race.
            duplicateOf(actor, input, index)?.let { return it }
            log.error("Cyberdef ticket create failed actor={} account={} exceptionType={}",
                actor.userId, input.awsAccountId, e.javaClass.simpleName, e)
            return rejected(index, CyberdefTicketResult.INTERNAL_ERROR, listOf("ticket could not be stored"), dryRun = false)
        }
        dispatchAsync(written.notificationIds)
        val ticket = written.ticket
        log.info("AUDIT operation=CYBERDEF_TICKET_CREATE actorId={} ticketId={} account={} severity={} recipients={} overridden={} outcome=CREATED",
            actor.userId, ticket.id, ticket.awsAccountId, ticket.severity, ticket.recipientCount, overridden)
        return CyberdefCreateResultDto(
            index = index, outcome = OUTCOME_CREATED, ticketId = ticket.id, ticketKey = ticket.ticketKey,
            status = ticket.status.name, recipientCount = ticket.recipientCount, recipients = emails,
            recipientsOverridden = overridden, dueAt = ticket.dueAt
        )
    }

    /** Where a ticket's mail goes, or why it may not be created. */
    private sealed class Routed {
        /** Mail [routing]'s recipients. */
        data class To(val routing: CyberdefRouting) : Routed()

        /** Refused with a stable error [code]. */
        data class Refused(val code: String, val message: String) : Routed()
    }

    /** Explicit recipients replace the account's owners; otherwise the owners must exist or be waived. */
    private fun route(input: ValidTicket): Routed {
        if (input.recipientOverride.isNotEmpty()) {
            return when (val override = resolver.resolveOverride(input.awsAccountId, input.recipientOverride)) {
                is CyberdefRecipientResolver.Override.Accepted -> Routed.To(override.routing)
                is CyberdefRecipientResolver.Override.Refused -> Routed.Refused(CyberdefTicketResult.RECIPIENT_NOT_ALLOWED,
                    "SecMan may not mail ${override.addresses.size} of the requested recipients " +
                        "(${override.addresses.take(3).joinToString()}); use addresses known to SecMan or an allowed domain")
            }
        }
        val routing = resolver.resolve(input.awsAccountId)
        if (routing.recipients.isEmpty() && !input.allowUnrouted) {
            return Routed.Refused(UNROUTABLE_ACCOUNT,
                "nobody is allocated to AWS account ${input.awsAccountId}; map an owner first, name recipients, or resend with allowUnrouted=true")
        }
        if (routing.truncated) {
            log.warn("Cyberdef ticket recipients capped account={} cap={}", input.awsAccountId, settings.maxRecipients)
        }
        return Routed.To(routing)
    }

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
                dispatchAsync(outcome.written.notificationIds)
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

    private fun rejected(index: Int, code: String, errors: List<String>, dryRun: Boolean) =
        CyberdefCreateResultDto(index = index, outcome = OUTCOME_REJECTED, errorCode = code, dryRun = dryRun,
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
        const val OUTCOME_WOULD_CREATE = "WOULD_CREATE"
        private val MAX_DUE_WITHOUT_SLA: Duration = Duration.ofDays(365)
    }
}
