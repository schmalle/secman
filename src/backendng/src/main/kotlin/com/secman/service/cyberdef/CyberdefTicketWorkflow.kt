package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind
import com.secman.domain.CyberdefActorKind.DEFENDER
import com.secman.domain.CyberdefActorKind.OWNER
import com.secman.domain.CyberdefTicketStatus
import com.secman.domain.CyberdefTicketStatus.*

/**
 * The only place that decides which status change is legal, and for whom.
 *
 * Owners (people allocated to the account, plus SECCHAMPION/ADMIN) move a ticket
 * through the remediation states. Defenders (CYBERDEF, ADMIN) answer questions,
 * verify, reopen and cancel. ADMIN holds both kinds, so it can do either.
 *
 * Kept free of I/O so every from × to × actor combination is unit-testable.
 */
object CyberdefTicketWorkflow {

/** Who may make one move and whether it needs a comment. */
    data class Rule(val actor: CyberdefActorKind, val commentRequired: Boolean)

/** Verdict of [isAllowed]. */
    sealed class Check {
/** The move is legal; [actor] is the side it is recorded under. */
        data class Allowed(val actor: CyberdefActorKind) : Check()
/** The move is refused; [code] is INVALID_TRANSITION, FORBIDDEN or COMMENT_REQUIRED. */
        data class Denied(val code: String, val message: String) : Check()
    }

    const val INVALID_TRANSITION = "INVALID_TRANSITION"
    const val COMMENT_REQUIRED = "COMMENT_REQUIRED"
    const val FORBIDDEN = "FORBIDDEN"

    /** Owner-side states in which the account team still has to act. */
    val OWNER_ACTION_STATUSES: Set<CyberdefTicketStatus> = setOf(NEW, ACKNOWLEDGED, IN_PROGRESS, REOPENED)

    /** Defender-side states that wait on CYBERDEF: a question, or a verification. */
    val DEFENDER_ACTION_STATUSES: Set<CyberdefTicketStatus> = setOf(NEEDS_INFO, RESOLVED, RISK_ACCEPTED, FALSE_POSITIVE)

    val OPEN_STATUSES: Set<CyberdefTicketStatus> = CyberdefTicketStatus.entries.filterNot { it.isTerminal }.toSet()

    private val RULES: Map<Pair<CyberdefTicketStatus, CyberdefTicketStatus>, Rule> = buildMap {
        fun owner(from: Collection<CyberdefTicketStatus>, target: CyberdefTicketStatus, comment: Boolean) =
            from.forEach { put(Pair(it, target), Rule(OWNER, comment)) }
        fun defender(from: Collection<CyberdefTicketStatus>, target: CyberdefTicketStatus, comment: Boolean) =
            from.forEach { put(Pair(it, target), Rule(DEFENDER, comment)) }

        owner(listOf(NEW), ACKNOWLEDGED, comment = false)
        owner(listOf(NEW, ACKNOWLEDGED, REOPENED), IN_PROGRESS, comment = false)
        owner(listOf(NEW, ACKNOWLEDGED, IN_PROGRESS, REOPENED), NEEDS_INFO, comment = true)
        owner(listOf(ACKNOWLEDGED, IN_PROGRESS, REOPENED), RESOLVED, comment = true)
        owner(listOf(ACKNOWLEDGED, IN_PROGRESS, REOPENED), RISK_ACCEPTED, comment = true)
        owner(listOf(NEW, ACKNOWLEDGED, IN_PROGRESS, REOPENED), FALSE_POSITIVE, comment = true)

        defender(listOf(NEEDS_INFO), IN_PROGRESS, comment = true)
        defender(listOf(RESOLVED, RISK_ACCEPTED, FALSE_POSITIVE), CLOSED, comment = false)
        defender(listOf(RESOLVED, RISK_ACCEPTED, FALSE_POSITIVE), REOPENED, comment = true)
        defender(listOf(NEW), CANCELLED, comment = false)
        defender(listOf(ACKNOWLEDGED, IN_PROGRESS, NEEDS_INFO, REOPENED), CANCELLED, comment = true)
    }

    fun rule(from: CyberdefTicketStatus, target: CyberdefTicketStatus): Rule? = RULES[Pair(from, target)]

    /** Targets reachable from [from] for someone holding [kinds], in declaration order. */
    fun allowedTransitions(from: CyberdefTicketStatus, kinds: Set<CyberdefActorKind>): List<CyberdefTicketStatus> =
        CyberdefTicketStatus.entries.filter { target -> rule(from, target)?.actor?.let { it in kinds } == true }

    /** Targets from [from] that need a comment, so the UI can mark them up front. */
    fun commentRequiredTargets(from: CyberdefTicketStatus): Set<CyberdefTicketStatus> =
        CyberdefTicketStatus.entries.filter { rule(from, it)?.commentRequired == true }.toSet()

    /** Decides one requested move for someone holding [kinds]; checked again inside the write transaction. */
    fun isAllowed(
        from: CyberdefTicketStatus,
        to: CyberdefTicketStatus,
        kinds: Set<CyberdefActorKind>,
        comment: String?
    ): Check {
        val match = rule(from, to)
            ?: return Check.Denied(INVALID_TRANSITION, "A ticket in status $from cannot move to $to")
        if (match.actor !in kinds) {
            val side = if (match.actor == OWNER) "the account owners" else "Cyber Defence"
            return Check.Denied(FORBIDDEN, "Only $side may move a ticket from $from to $to")
        }
        if (match.commentRequired && comment.isNullOrBlank()) {
            return Check.Denied(COMMENT_REQUIRED, "A comment is required to move a ticket to $to")
        }
        return Check.Allowed(match.actor)
    }
}
