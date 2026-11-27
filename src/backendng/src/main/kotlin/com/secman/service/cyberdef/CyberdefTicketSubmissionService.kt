package com.secman.service.cyberdef

import com.secman.domain.CyberdefTicket
import com.secman.domain.CyberdefTicketStatus
import com.secman.dto.CyberdefBatchResultDto
import com.secman.dto.CyberdefCreateResultDto
import com.secman.repository.CyberdefTicketRepository
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.RATE_LIMITED
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.RECIPIENT_NOT_ALLOWED
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.UNROUTABLE_ACCOUNT
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.VALIDATION_ERROR
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.defendersOnly
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Creates tickets: authorises, validates, routes, then stores each ticket in its own
 * transaction through [CyberdefTicketWriter] and hands the queued mail to
 * [CyberdefTicketMailer] after commit.
 *
 * Everything that does not depend on the individual ticket is done once per call: the
 * delivery options, the duplicate lookup, the hourly count and the recipient routing per
 * distinct account. A 100-ticket batch for one account therefore costs a handful of reads
 * instead of several per ticket. A dry run runs the same steps and stops before writing.
 */
@Singleton
open class CyberdefTicketSubmissionService(
    private val tickets: CyberdefTicketRepository,
    private val writer: CyberdefTicketWriter,
    private val mailer: CyberdefTicketMailer,
    private val resolver: CyberdefRecipientResolver,
    private val settings: CyberdefTicketSettings
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Submits one ticket. A rejection is a REJECTED result with field errors, as in a batch. */
    open fun create(actor: CyberdefTicketActor, request: CyberdefTicketCreateRequest): CyberdefTicketResult<CyberdefCreateResultDto> {
        val ticket = request.ticket ?: return CyberdefTicketResult.Failed(VALIDATION_ERROR, "ticket is required",
            listOf(CyberdefFieldError("ticket", CyberdefFieldError.REQUIRED, "ticket is required")))
        return when (val batch = createBatch(actor, CyberdefBatchCreateRequest(listOf(ticket), request.delivery))) {
            is CyberdefTicketResult.Failed -> batch
            is CyberdefTicketResult.Ok -> CyberdefTicketResult.Ok(batch.value.results.single())
        }
    }

    /** Submits 1-100 tickets, each in its own transaction, and reports every item. */
    open fun createBatch(actor: CyberdefTicketActor, request: CyberdefBatchCreateRequest): CyberdefTicketResult<CyberdefBatchResultDto> {
        if (!actor.isDefender) return defendersOnly
        val items = request.tickets.orEmpty()
        if (items.isEmpty() || items.size > CyberdefTicketInput.BATCH_MAX) {
            return CyberdefTicketResult.Failed(VALIDATION_ERROR, "tickets must contain 1-${CyberdefTicketInput.BATCH_MAX} items",
                listOf(CyberdefFieldError("tickets", CyberdefFieldError.TOO_MANY, "1-${CyberdefTicketInput.BATCH_MAX} tickets")))
        }
        val delivery = when (val checked = CyberdefTicketInput.validateDelivery(request.delivery)) {
            is Validated.Invalid -> return CyberdefTicketResult.Failed(VALIDATION_ERROR, "invalid delivery options", checked.errors)
            is Validated.Valid -> checked.value
        }
        val now = Instant.now()
        val validated = items.map { CyberdefTicketInput.validate(it, now) }
        val context = BatchContext(actor, delivery, now, existingTickets(actor, validated))
        val results = validated.mapIndexed { index, outcome -> submit(context, index, outcome) }
        if (!delivery.dryRun) mailer.dispatchLater(context.queuedMail)
        return CyberdefTicketResult.Ok(
            CyberdefBatchResultDto(
                dryRun = delivery.dryRun,
                created = results.count { it.outcome == OUTCOME_CREATED || it.outcome == OUTCOME_WOULD_CREATE },
                duplicates = results.count { it.outcome == OUTCOME_DUPLICATE },
                rejected = results.count { it.outcome == OUTCOME_REJECTED },
                results = results
            )
        )
    }

    /** Per-call state shared by every ticket of one submission. */
    private inner class BatchContext(
        val actor: CyberdefTicketActor,
        val delivery: ValidDelivery,
        val now: Instant,
        val existing: MutableMap<String, CyberdefTicket>
    ) {
        /** Groups this call's mail, so each recipient gets one digest for the whole batch. */
        val batchKey: String = UUID.randomUUID().toString()
        val queuedMail = mutableListOf<Long>()
        private val routes = mutableMapOf<String, Routed>()

        /** Override recipients are checked once per call; only the account name differs per account. */
        val override: CyberdefRecipientResolver.Override? by lazy {
            delivery.recipients.takeIf { it.isNotEmpty() }?.let(resolver::checkOverride)
        }
        private var budget: Long = settings.maxTicketsPerHour -
            tickets.countByCreatedByUserIdAndCreatedAtAfter(actor.userId, now.minus(Duration.ofHours(1)))

        fun route(accountId: String): Routed = routes.getOrPut(accountId) { routeAccount(accountId, delivery, override) }

        /** Takes one slot of the hourly budget; dry runs only look. */
        fun takeBudget(): Boolean {
            if (budget <= 0) return false
            if (!delivery.dryRun) budget--
            return true
        }
    }

    private fun existingTickets(actor: CyberdefTicketActor, validated: List<Validated<ValidTicket>>): MutableMap<String, CyberdefTicket> {
        val references = validated.mapNotNull { (it as? Validated.Valid)?.value?.externalReference }.distinct()
        if (references.isEmpty()) return mutableMapOf()
        return tickets.findByCreatedByUserIdAndExternalReferenceIn(actor.userId, references)
            .associateByTo(mutableMapOf()) { it.externalReference!! }
    }

    private fun submit(context: BatchContext, index: Int, outcome: Validated<ValidTicket>): CyberdefCreateResultDto {
        val dryRun = context.delivery.dryRun
        val input = when (outcome) {
            is Validated.Invalid -> return rejected(index, VALIDATION_ERROR, outcome.errors, dryRun)
            is Validated.Valid -> outcome.value
        }
        input.externalReference?.let { context.existing[it] }?.let { return duplicate(index, it, dryRun) }
        val dueAt = when (val due = dueDate(input, context.now)) {
            is DueDate.Invalid -> return rejected(index, VALIDATION_ERROR, listOf(CyberdefFieldError("dueAt", CyberdefFieldError.NOT_ALLOWED, due.message)), dryRun)
            is DueDate.Valid -> due.value
        }
        val routing = when (val routed = context.route(input.awsAccountId)) {
            is Routed.Refused -> return rejected(index, routed.code, routed.errors, dryRun)
            is Routed.To -> routed.routing
        }
        if (!context.takeBudget()) {
            return rejected(index, RATE_LIMITED, listOf(CyberdefFieldError("ticket", RATE_LIMITED,
                "at most ${settings.maxTicketsPerHour} tickets per hour")), dryRun)
        }
        val emails = routing.recipients.map { it.email }
        val overridden = context.delivery.recipients.isNotEmpty()
        if (dryRun) {
            log.info("AUDIT operation=CYBERDEF_TICKET_CREATE actorId={} account={} recipients={} overridden={} outcome=DRY_RUN",
                context.actor.userId, input.awsAccountId, emails.size, overridden)
            return CyberdefCreateResultDto(index = index, outcome = OUTCOME_WOULD_CREATE, status = CyberdefTicketStatus.OPEN.name,
                recipientCount = emails.size, recipients = emails, recipientsOverridden = overridden, dueAt = dueAt, dryRun = true)
        }
        return store(context, index, input, routing, dueAt, overridden)
    }

    private fun store(
        context: BatchContext,
        index: Int,
        input: ValidTicket,
        routing: CyberdefRouting,
        dueAt: Instant?,
        overridden: Boolean
    ): CyberdefCreateResultDto {
        val written = try {
            writer.create(context.actor, input, routing, dueAt, context.batchKey)
        } catch (e: RuntimeException) {
            // A concurrent submission with the same externalReference loses the unique key race.
            input.externalReference?.let { tickets.findByCreatedByUserIdAndExternalReference(context.actor.userId, it).orElse(null) }
                ?.let { return duplicate(index, it, dryRun = false) }
            log.error("Cyberdef ticket create failed actor={} account={} exceptionType={}",
                context.actor.userId, input.awsAccountId, e.javaClass.simpleName, e)
            return rejected(index, CyberdefTicketResult.INTERNAL_ERROR,
                listOf(CyberdefFieldError("ticket", CyberdefTicketResult.INTERNAL_ERROR, "ticket could not be stored")), dryRun = false)
        }
        context.queuedMail += written.notificationIds
        val ticket = written.ticket
        input.externalReference?.let { context.existing[it] = ticket }
        log.info("AUDIT operation=CYBERDEF_TICKET_CREATE actorId={} ticketId={} account={} severity={} recipients={} overridden={} outcome=CREATED",
            context.actor.userId, ticket.id, ticket.awsAccountId, ticket.severity, ticket.recipientCount, overridden)
        return CyberdefCreateResultDto(
            index = index, outcome = OUTCOME_CREATED, ticketId = ticket.id, ticketKey = ticket.ticketKey,
            status = ticket.status.name, recipientCount = ticket.recipientCount, recipients = routing.recipients.map { it.email },
            recipientsOverridden = overridden, dueAt = ticket.dueAt
        )
    }

    /** Where a ticket's mail goes, or why it may not be created. */
    sealed class Routed {
        /** Mail [routing]'s recipients. */
        data class To(val routing: CyberdefRouting) : Routed()

        /** Refused with a stable error [code]. */
        data class Refused(val code: String, val errors: List<CyberdefFieldError>) : Routed()
    }

    /** Explicit recipients replace the account's owners; otherwise the owners must exist or be waived. */
    private fun routeAccount(accountId: String, delivery: ValidDelivery, override: CyberdefRecipientResolver.Override?): Routed {
        if (override != null) {
            return when (override) {
                is CyberdefRecipientResolver.Override.Accepted ->
                    Routed.To(CyberdefRouting(override.recipients, resolver.accountName(accountId), truncated = false))
                is CyberdefRecipientResolver.Override.Refused -> Routed.Refused(RECIPIENT_NOT_ALLOWED,
                    delivery.recipients.withIndex().filter { it.value in override.addresses }.map { (i, address) ->
                        CyberdefFieldError("delivery.recipients[$i]", CyberdefFieldError.NOT_ALLOWED,
                            "SecMan may not mail $address: not a SecMan user or mapping, and not on an allowed domain")
                    })
            }
        }
        val routing = resolver.resolve(accountId)
        if (routing.recipients.isEmpty() && !delivery.allowUnrouted) {
            return Routed.Refused(UNROUTABLE_ACCOUNT, listOf(CyberdefFieldError("awsAccountId", UNROUTABLE_ACCOUNT,
                "nobody is allocated to AWS account $accountId; map an owner, name recipients, or allow unrouted tickets")))
        }
        if (routing.truncated) log.warn("Cyberdef ticket recipients capped account={} cap={}", accountId, settings.maxRecipients)
        return Routed.To(routing)
    }

    private fun duplicate(index: Int, existing: CyberdefTicket, dryRun: Boolean) = CyberdefCreateResultDto(
        index = index, outcome = OUTCOME_DUPLICATE, ticketId = existing.id, ticketKey = existing.ticketKey,
        status = existing.status.name, recipientCount = existing.recipientCount, dueAt = existing.dueAt, dryRun = dryRun
    )

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

    private fun rejected(index: Int, code: String, errors: List<CyberdefFieldError>, dryRun: Boolean) =
        CyberdefCreateResultDto(index = index, outcome = OUTCOME_REJECTED, errorCode = code, dryRun = dryRun,
            errors = errors.map { it.copy(message = CyberdefTicketInput.cleanSingleLine(it.message).take(300)) })

    companion object {
        const val OUTCOME_CREATED = "CREATED"
        const val OUTCOME_DUPLICATE = "DUPLICATE"
        const val OUTCOME_REJECTED = "REJECTED"
        const val OUTCOME_WOULD_CREATE = "WOULD_CREATE"
        private val MAX_DUE_WITHOUT_SLA: Duration = Duration.ofDays(365)
    }
}
