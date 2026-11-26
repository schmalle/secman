package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind
import io.micronaut.security.authentication.Authentication

/**
 * The caller of a ticket operation, built from either a web session or an MCP delegation,
 * so both surfaces go through exactly the same checks in [CyberdefTicketService].
 */
data class CyberdefTicketActor(
    val userId: Long,
    val username: String,
    val email: String?,
    val roles: Set<String>
) {
    val isAdmin: Boolean get() = "ADMIN" in roles
    val isDefender: Boolean get() = isAdmin || "CYBERDEF" in roles

    /** Roles that see every ticket: the defence team itself plus the two global asset roles. */
    val hasGlobalView: Boolean get() = isDefender || "SECCHAMPION" in roles

    /** ADMIN and SECCHAMPION act as owners on every account, matching their global asset authority. */
    val isGlobalOwner: Boolean get() = isAdmin || "SECCHAMPION" in roles

    /** Workflow sides held for a ticket, given whether the caller can access its account. */
    fun kindsFor(hasAccountAccess: Boolean): Set<CyberdefActorKind> = buildSet {
        if (isGlobalOwner || hasAccountAccess) add(CyberdefActorKind.OWNER)
        if (isDefender) add(CyberdefActorKind.DEFENDER)
    }

    companion object {
        fun from(authentication: Authentication): CyberdefTicketActor = CyberdefTicketActor(
            userId = authentication.attributes["userId"]?.toString()?.toLongOrNull()
                ?: throw IllegalStateException("User ID not found in authentication"),
            username = authentication.name,
            email = authentication.attributes["email"]?.toString(),
            roles = authentication.roles.map { it.uppercase() }.toSet()
        )
    }
}
