package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind
import com.secman.domain.CyberdefActorKind.DEFENDER
import com.secman.domain.CyberdefActorKind.OWNER
import com.secman.domain.CyberdefTicketStatus
import com.secman.domain.CyberdefTicketStatus.CLOSED
import com.secman.domain.CyberdefTicketStatus.OPEN

/**
 * The only place that decides which status change is legal, and for whom.
 *
 * Two states, two moves:
 * - OPEN → CLOSED: the account owners close with a comment saying what they did;
 *   Cyber Defence may close without one (e.g. a finding withdrawn or verified elsewhere).
 * - CLOSED → OPEN: either side reopens, always with a comment saying why.
 *
 * Owners are the people allocated to the account plus SECCHAMPION/ADMIN; defenders are
 * CYBERDEF and ADMIN. Kept free of I/O so every combination is unit-testable.
 */
object CyberdefTicketWorkflow {

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

    /**
     * (from, to) → side → comment required. Sides are listed defender first: when a caller
     * holds both (ADMIN), the move is recorded as the defender's.
     */
    private val RULES: Map<Pair<CyberdefTicketStatus, CyberdefTicketStatus>, Map<CyberdefActorKind, Boolean>> = mapOf(
        Pair(OPEN, CLOSED) to linkedMapOf(DEFENDER to false, OWNER to true),
        Pair(CLOSED, OPEN) to linkedMapOf(DEFENDER to true, OWNER to true)
    )

    /** Sides allowed to move [from] → [target], with whether each needs a comment. */
    fun sides(from: CyberdefTicketStatus, target: CyberdefTicketStatus): Map<CyberdefActorKind, Boolean> =
        RULES[Pair(from, target)].orEmpty()

    /** Targets reachable from [from] for someone holding [kinds]. */
    fun allowedTransitions(from: CyberdefTicketStatus, kinds: Set<CyberdefActorKind>): List<CyberdefTicketStatus> =
        CyberdefTicketStatus.entries.filter { target -> sides(from, target).keys.any { it in kinds } }

    /** Allowed targets for which every side the caller holds needs a comment, so the UI can ask up front. */
    fun commentRequiredTargets(from: CyberdefTicketStatus, kinds: Set<CyberdefActorKind>): Set<CyberdefTicketStatus> =
        allowedTransitions(from, kinds).filter { target ->
            sides(from, target).filterKeys { it in kinds }.values.all { it }
        }.toSet()

    /** Decides one requested move for someone holding [kinds]; checked again inside the write transaction. */
    fun isAllowed(
        from: CyberdefTicketStatus,
        to: CyberdefTicketStatus,
        kinds: Set<CyberdefActorKind>,
        comment: String?
    ): Check {
        val sides = sides(from, to)
        if (sides.isEmpty()) return Check.Denied(INVALID_TRANSITION, "A ticket in status $from cannot move to $to")
        val held = sides.filterKeys { it in kinds }
        if (held.isEmpty()) return Check.Denied(FORBIDDEN, "You may not move this ticket from $from to $to")
        val hasComment = !comment.isNullOrBlank()
        val side = held.entries.firstOrNull { (_, needsComment) -> hasComment || !needsComment }?.key
            ?: return Check.Denied(COMMENT_REQUIRED, "A comment is required to move a ticket to $to")
        return Check.Allowed(side)
    }
}
