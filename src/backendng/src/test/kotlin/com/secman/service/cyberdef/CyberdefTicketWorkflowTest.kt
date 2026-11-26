package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind.DEFENDER
import com.secman.domain.CyberdefActorKind.OWNER
import com.secman.domain.CyberdefTicketStatus
import com.secman.domain.CyberdefTicketStatus.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** The transition table is an authorization boundary, so these assertions are exact. */
class CyberdefTicketWorkflowTest {

    @ParameterizedTest
    @EnumSource(value = CyberdefTicketStatus::class, names = ["CLOSED", "CANCELLED"])
    fun `terminal statuses allow no further transition for anyone`(terminal: CyberdefTicketStatus) {
        assertThat(CyberdefTicketWorkflow.allowedTransitions(terminal, setOf(OWNER, DEFENDER))).isEmpty()
    }

    @Test
    fun `owners drive remediation and defenders verify`() {
        assertThat(CyberdefTicketWorkflow.allowedTransitions(NEW, setOf(OWNER)))
            .containsExactly(ACKNOWLEDGED, IN_PROGRESS, NEEDS_INFO, FALSE_POSITIVE)
        assertThat(CyberdefTicketWorkflow.allowedTransitions(NEW, setOf(DEFENDER))).containsExactly(CANCELLED)
        assertThat(CyberdefTicketWorkflow.allowedTransitions(RESOLVED, setOf(OWNER))).isEmpty()
        assertThat(CyberdefTicketWorkflow.allowedTransitions(RESOLVED, setOf(DEFENDER))).containsExactly(REOPENED, CLOSED)
        assertThat(CyberdefTicketWorkflow.allowedTransitions(NEEDS_INFO, setOf(OWNER))).isEmpty()
        assertThat(CyberdefTicketWorkflow.allowedTransitions(NEEDS_INFO, setOf(DEFENDER))).containsExactly(IN_PROGRESS, CANCELLED)
    }

    @Test
    fun `an owner can never close and a defender can never resolve`() {
        for (from in CyberdefTicketStatus.entries) {
            assertThat(CyberdefTicketWorkflow.allowedTransitions(from, setOf(OWNER))).doesNotContain(CLOSED, CANCELLED, REOPENED)
            assertThat(CyberdefTicketWorkflow.allowedTransitions(from, setOf(DEFENDER)))
                .doesNotContain(ACKNOWLEDGED, RESOLVED, RISK_ACCEPTED, FALSE_POSITIVE, NEEDS_INFO)
        }
    }

    @Test
    fun `admin holding both sides gets the union`() {
        assertThat(CyberdefTicketWorkflow.allowedTransitions(NEW, setOf(OWNER, DEFENDER)))
            .containsExactlyInAnyOrder(ACKNOWLEDGED, IN_PROGRESS, NEEDS_INFO, FALSE_POSITIVE, CANCELLED)
    }

    @Test
    fun `check reports the reason a move is refused`() {
        val invalid = CyberdefTicketWorkflow.isAllowed(NEW, CLOSED, setOf(DEFENDER), "x")
        assertThat((invalid as CyberdefTicketWorkflow.Check.Denied).code).isEqualTo(CyberdefTicketWorkflow.INVALID_TRANSITION)

        val wrongSide = CyberdefTicketWorkflow.isAllowed(IN_PROGRESS, RESOLVED, setOf(DEFENDER), "done")
        assertThat((wrongSide as CyberdefTicketWorkflow.Check.Denied).code).isEqualTo(CyberdefTicketWorkflow.FORBIDDEN)

        val noComment = CyberdefTicketWorkflow.isAllowed(IN_PROGRESS, RESOLVED, setOf(OWNER), "  ")
        assertThat((noComment as CyberdefTicketWorkflow.Check.Denied).code).isEqualTo(CyberdefTicketWorkflow.COMMENT_REQUIRED)

        assertThat(CyberdefTicketWorkflow.isAllowed(IN_PROGRESS, RESOLVED, setOf(OWNER), "patched"))
            .isEqualTo(CyberdefTicketWorkflow.Check.Allowed(OWNER))
        assertThat(CyberdefTicketWorkflow.isAllowed(NEW, ACKNOWLEDGED, setOf(OWNER), null))
            .isEqualTo(CyberdefTicketWorkflow.Check.Allowed(OWNER))
    }

    @Test
    fun `every answer, dispute, reopen and late cancellation needs a comment`() {
        val required = listOf(
            NEW to NEEDS_INFO, IN_PROGRESS to RESOLVED, IN_PROGRESS to RISK_ACCEPTED, NEW to FALSE_POSITIVE,
            NEEDS_INFO to IN_PROGRESS, RESOLVED to REOPENED, IN_PROGRESS to CANCELLED
        )
        required.forEach { (from, to) -> assertThat(CyberdefTicketWorkflow.rule(from, to)!!.commentRequired).isTrue() }
        listOf(NEW to ACKNOWLEDGED, RESOLVED to CLOSED, NEW to CANCELLED)
            .forEach { (from, to) -> assertThat(CyberdefTicketWorkflow.rule(from, to)!!.commentRequired).isFalse() }
    }

    @Test
    fun `every non-terminal status can still reach a terminal one`() {
        val both = setOf(OWNER, DEFENDER)
        for (start in CyberdefTicketWorkflow.OPEN_STATUSES) {
            val seen = mutableSetOf(start)
            var frontier = listOf(start)
            while (frontier.isNotEmpty()) {
                frontier = frontier.flatMap { CyberdefTicketWorkflow.allowedTransitions(it, both) }.filter { seen.add(it) }
            }
            assertThat(seen).describedAs("reachable from $start").containsAnyOf(CLOSED, CANCELLED)
        }
    }
}
