package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind
import com.secman.domain.CyberdefTicket
import com.secman.repository.CyberdefTicketRepository
import com.secman.service.AssetFilterService
import jakarta.inject.Singleton

/** Account scope of a caller: every account, or exactly [accountIds]. */
data class CyberdefAccountScope(val all: Boolean, val accountIds: Set<String>) {
    fun covers(accountId: String): Boolean = all || accountId in accountIds

    /** Never empty, because an empty JPQL IN list is invalid; ignored when [all] is set. */
    val queryIds: Collection<String> get() = accountIds.ifEmpty { setOf(NO_ACCOUNT) }

    companion object {
        private const val NO_ACCOUNT = "-"
    }
}

/**
 * Who may see and act on a ticket. Account access is the same rule the asset inventory
 * uses ([AssetFilterService.getAccessibleAwsAccountIds]); this class only adds the global
 * roles of the ticket feature on top.
 */
@Singleton
open class CyberdefTicketAccess(
    private val tickets: CyberdefTicketRepository,
    private val assetFilter: AssetFilterService
) {
    /** Evaluated once per request (see [CyberdefTicketActor.accountsForRequest]), never cached longer. */
    open fun ownAccounts(actor: CyberdefTicketActor): Set<String> =
        actor.accountsForRequest { assetFilter.getAccessibleAwsAccountIds(actor.userId, actor.email) }

    open fun scope(actor: CyberdefTicketActor): CyberdefAccountScope =
        if (actor.hasGlobalView) CyberdefAccountScope(true, emptySet())
        else CyberdefAccountScope(false, ownAccounts(actor))

    /** The ticket, or null when it does not exist or lies outside the caller's scope. */
    open fun loadVisible(actor: CyberdefTicketActor, ticketId: Long): CyberdefTicket? {
        val ticket = tickets.findById(ticketId).orElse(null) ?: return null
        return ticket.takeIf { actor.hasGlobalView || it.awsAccountId in ownAccounts(actor) }
    }

    /** Workflow sides the caller holds for [ticket]. Empty means read-only at most. */
    open fun kinds(actor: CyberdefTicketActor, ticket: CyberdefTicket): Set<CyberdefActorKind> {
        val accountAccess = !actor.isGlobalOwner && ticket.awsAccountId in ownAccounts(actor)
        return actor.kindsFor(accountAccess)
    }
}
