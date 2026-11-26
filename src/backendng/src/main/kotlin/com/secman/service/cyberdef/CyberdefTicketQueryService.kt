package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind
import com.secman.domain.CyberdefSeverity
import com.secman.domain.CyberdefTicket
import com.secman.domain.CyberdefTicketStatus
import com.secman.dto.CyberdefNotificationDto
import com.secman.dto.CyberdefTicketCountsDto
import com.secman.dto.CyberdefTicketDetailDto
import com.secman.dto.CyberdefTicketEventDto
import com.secman.dto.CyberdefTicketPageDto
import com.secman.dto.CyberdefTicketResourceDto
import com.secman.dto.CyberdefTicketSummaryDto
import com.secman.dto.CyberdefUpdatesDto
import com.secman.repository.CyberdefTicketEventRepository
import com.secman.repository.CyberdefTicketNotificationRepository
import com.secman.repository.CyberdefTicketRepository
import com.secman.repository.CyberdefTicketResourceRepository
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.defendersOnly
import com.secman.service.cyberdef.CyberdefTicketResult.Companion.notFound
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import jakarta.inject.Singleton
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** List filters. Everything is optional; [page]/[size] are clamped, [sort] is allowlisted. */
data class CyberdefTicketFilter(
    val statuses: Set<CyberdefTicketStatus> = emptySet(),
    val severities: Set<CyberdefSeverity> = emptySet(),
    val accountId: String? = null,
    val createdByMe: Boolean = false,
    val overdueOnly: Boolean = false,
    val updatedSince: Instant? = null,
    val search: String? = null,
    val page: Int = 0,
    val size: Int = 25,
    val sort: String = "updatedAt",
    val descending: Boolean = true
)

/** Read side of the feature. Every read is scoped through [CyberdefTicketAccess]. */
@Singleton
open class CyberdefTicketQueryService(
    private val tickets: CyberdefTicketRepository,
    private val resources: CyberdefTicketResourceRepository,
    private val events: CyberdefTicketEventRepository,
    private val notifications: CyberdefTicketNotificationRepository,
    private val access: CyberdefTicketAccess
) {

    /** One page of tickets in the caller's scope matching [filter]. */
    open fun list(actor: CyberdefTicketActor, filter: CyberdefTicketFilter): CyberdefTicketPageDto {
        val page = filter.page.coerceAtLeast(0)
        val size = filter.size.coerceIn(1, MAX_PAGE_SIZE)
        val scope = access.scope(actor)
        if (!scope.all && scope.accountIds.isEmpty()) return CyberdefTicketPageDto(emptyList(), 0, 0, page, size)

        val sortField = SORTABLE[filter.sort] ?: "updatedAt"
        val order = if (filter.descending) Sort.Order.desc(sortField) else Sort.Order.asc(sortField)
        val now = Instant.now()
        val requested = filter.statuses.ifEmpty { CyberdefTicketStatus.entries.toSet() }
        val statuses = if (filter.overdueOnly) requested intersect OPEN_ONLY else requested
        if (statuses.isEmpty()) return CyberdefTicketPageDto(emptyList(), 0, 0, page, size)
        val search = filter.search?.let(CyberdefTicketInput::cleanSingleLine)?.take(SEARCH_MAX)?.takeIf { it.isNotEmpty() }

        val result = tickets.search(
            allAccounts = scope.all, accountIds = scope.queryIds, statuses = statuses,
            severities = filter.severities.ifEmpty { CyberdefSeverity.entries.toSet() },
            accountId = filter.accountId?.trim()?.takeIf { it.isNotEmpty() },
            createdBy = if (filter.createdByMe) actor.userId else null,
            dueBefore = if (filter.overdueOnly) now else null,
            updatedSince = filter.updatedSince,
            search = search?.let { "%" + escapeLike(it.lowercase(Locale.ROOT)) + "%" },
            searchKey = search?.uppercase(Locale.ROOT),
            pageable = Pageable.from(page, size, Sort.of(order))
        )
        return CyberdefTicketPageDto(
            result.content.map { CyberdefTicketSummaryDto.from(it, now) },
            result.totalSize, result.totalPages, page, size
        )
    }

    /** Status counts, overdue and needs-my-action figures within the caller's scope. */
    open fun counts(actor: CyberdefTicketActor): CyberdefTicketCountsDto {
        val scope = access.scope(actor)
        if (!scope.all && scope.accountIds.isEmpty()) return CyberdefTicketCountsDto(0, 0, 0, 0)
        val byStatus = tickets.countByStatus(scope.all, scope.queryIds)
            .associate { row -> row[0] as CyberdefTicketStatus to (row[1] as Number).toLong() }
        val open = byStatus[CyberdefTicketStatus.OPEN] ?: 0L
        val closed = byStatus[CyberdefTicketStatus.CLOSED] ?: 0L
        val overdue = tickets.countOverdue(scope.all, scope.queryIds, OPEN_ONLY, Instant.now())
        return CyberdefTicketCountsDto(total = open + closed, open = open, closed = closed, overdue = overdue)
    }

    /** One ticket, or NOT_FOUND when it is missing or outside the caller's accounts. */
    open fun detail(actor: CyberdefTicketActor, ticketId: Long): CyberdefTicketResult<CyberdefTicketDetailDto> {
        val ticket = access.loadVisible(actor, ticketId) ?: return notFound
        return CyberdefTicketResult.Ok(detailOf(actor, ticket))
    }

    /**
     * Lookup by the human key (`CD-2026-000123`) or by the submitter's own external
     * reference. External references are unique per submitter only, so that lookup is
     * restricted to tickets the caller created.
     */
    open fun find(actor: CyberdefTicketActor, ticketKey: String?, externalReference: String?): CyberdefTicketResult<CyberdefTicketDetailDto> {
        val ticket = when {
            !ticketKey.isNullOrBlank() -> tickets.findByTicketKey(ticketKey.trim().uppercase(Locale.ROOT)).orElse(null)
            !externalReference.isNullOrBlank() ->
                tickets.findByCreatedByUserIdAndExternalReference(actor.userId, externalReference.trim()).orElse(null)
            else -> null
        } ?: return notFound
        return detail(actor, ticket.id!!)
    }

    /** Builds the detail view of a ticket the caller is already known to see. */
    fun detailOf(actor: CyberdefTicketActor, ticket: CyberdefTicket): CyberdefTicketDetailDto {
        val id = ticket.id!!
        val kinds = access.kinds(actor, ticket)
        val allowed = CyberdefTicketWorkflow.allowedTransitions(ticket.status, kinds)
        val needComment = CyberdefTicketWorkflow.commentRequiredTargets(ticket.status, kinds)
        return CyberdefTicketDetailDto(
            ticket = CyberdefTicketSummaryDto.from(ticket),
            description = ticket.description, recommendedAction = ticket.recommendedAction,
            cves = ticket.cveList(), resources = resources.findByTicketIdOrderByIdAsc(id).map(CyberdefTicketResourceDto::from),
            references = ticket.referenceList(), recipientCount = ticket.recipientCount,
            closedAt = ticket.closedAt,
            events = events.findByTicketIdOrderByIdAsc(id, Pageable.from(0, MAX_DETAIL_EVENTS))
                .map { CyberdefTicketEventDto.from(it, ticket.ticketKey) },
            allowedTransitions = allowed.map { it.name },
            commentRequiredFor = allowed.filter { it in needComment }.map { it.name },
            canComment = kinds.isNotEmpty(),
            canManageDelivery = CyberdefActorKind.DEFENDER in kinds
        )
    }

    /**
     * Everything that happened after [sinceEventId], for pollers on the Cyber Defence side.
     * Restricted to roles with the global view, because it spans every account.
     */
    open fun updates(actor: CyberdefTicketActor, sinceEventId: Long, limit: Int): CyberdefTicketResult<CyberdefUpdatesDto> {
        if (!actor.hasGlobalView) return CyberdefTicketResult.Failed(CyberdefTicketResult.FORBIDDEN, "Only Cyber Defence may read the update feed")
        val cursor = sinceEventId.coerceAtLeast(0)
        val bounded = limit.coerceIn(1, MAX_UPDATES)
        val rows = events.findAfter(cursor, Instant.now().minus(SETTLE_WINDOW), Pageable.from(0, bounded + 1))
        val page = rows.take(bounded)
        val keys = if (page.isEmpty()) emptyMap()
            else tickets.findByIdIn(page.map { it.ticketId }.toSet()).associate { it.id!! to it.ticketKey }
        return CyberdefTicketResult.Ok(
            CyberdefUpdatesDto(
                events = page.map { CyberdefTicketEventDto.from(it, keys[it.ticketId]) },
                nextCursor = page.lastOrNull()?.id ?: cursor,
                hasMore = rows.size > bounded
            )
        )
    }

    /** Delivery state of a ticket's mail; Cyber Defence only. */
    open fun notifications(actor: CyberdefTicketActor, ticketId: Long): CyberdefTicketResult<List<CyberdefNotificationDto>> {
        if (!actor.isDefender) return defendersOnly
        access.loadVisible(actor, ticketId) ?: return notFound
        return CyberdefTicketResult.Ok(notifications.findByTicketIdOrderByIdAsc(ticketId).map(CyberdefNotificationDto::from))
    }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        const val MAX_PAGE_SIZE = 200
        const val MAX_UPDATES = 500
        const val MAX_DETAIL_EVENTS = 500
        private const val SEARCH_MAX = 100
        val SETTLE_WINDOW: Duration = Duration.ofSeconds(5)
        private val OPEN_ONLY = setOf(CyberdefTicketStatus.OPEN)

        /** Request sort key → entity property. Nothing else ever reaches ORDER BY. */
        private val SORTABLE = mapOf("updatedAt" to "updatedAt", "createdAt" to "createdAt", "dueAt" to "dueAt")
    }
}
