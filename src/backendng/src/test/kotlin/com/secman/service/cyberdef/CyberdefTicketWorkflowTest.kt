package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind.DEFENDER
import com.secman.domain.CyberdefActorKind.OWNER
import com.secman.domain.CyberdefTicketStatus.CLOSED
import com.secman.domain.CyberdefTicketStatus.OPEN
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The transition table is an authorization boundary, so these assertions are exact. */
class CyberdefTicketWorkflowTest {

    @Test
    fun `both sides can close an open ticket and reopen a closed one`() {
        for (kinds in listOf(setOf(OWNER), setOf(DEFENDER), setOf(OWNER, DEFENDER))) {
            assertThat(CyberdefTicketWorkflow.allowedTransitions(OPEN, kinds)).containsExactly(CLOSED)
            assertThat(CyberdefTicketWorkflow.allowedTransitions(CLOSED, kinds)).containsExactly(OPEN)
        }
    }

    @Test
    fun `nobody without a side may move a ticket`() {
        assertThat(CyberdefTicketWorkflow.allowedTransitions(OPEN, emptySet())).isEmpty()
        val denied = CyberdefTicketWorkflow.isAllowed(OPEN, CLOSED, emptySet(), "done")
        assertThat((denied as CyberdefTicketWorkflow.Check.Denied).code).isEqualTo(CyberdefTicketWorkflow.FORBIDDEN)
    }

    @Test
    fun `a move to the current status is invalid`() {
        val same = CyberdefTicketWorkflow.isAllowed(OPEN, OPEN, setOf(OWNER, DEFENDER), "x")
        assertThat((same as CyberdefTicketWorkflow.Check.Denied).code).isEqualTo(CyberdefTicketWorkflow.INVALID_TRANSITION)
    }

    @Test
    fun `owners must say what they did when closing, defence need not`() {
        val ownerSilent = CyberdefTicketWorkflow.isAllowed(OPEN, CLOSED, setOf(OWNER), " ")
        assertThat((ownerSilent as CyberdefTicketWorkflow.Check.Denied).code).isEqualTo(CyberdefTicketWorkflow.COMMENT_REQUIRED)
        assertThat(CyberdefTicketWorkflow.isAllowed(OPEN, CLOSED, setOf(OWNER), "patched"))
            .isEqualTo(CyberdefTicketWorkflow.Check.Allowed(OWNER))
        assertThat(CyberdefTicketWorkflow.isAllowed(OPEN, CLOSED, setOf(DEFENDER), null))
            .isEqualTo(CyberdefTicketWorkflow.Check.Allowed(DEFENDER))
    }

    @Test
    fun `reopening always needs a reason`() {
        for (kinds in listOf(setOf(OWNER), setOf(DEFENDER))) {
            val silent = CyberdefTicketWorkflow.isAllowed(CLOSED, OPEN, kinds, null)
            assertThat((silent as CyberdefTicketWorkflow.Check.Denied).code).isEqualTo(CyberdefTicketWorkflow.COMMENT_REQUIRED)
            assertThat(CyberdefTicketWorkflow.commentRequiredTargets(CLOSED, kinds)).containsExactly(OPEN)
        }
    }

    @Test
    fun `admin holding both sides is recorded as defence`() {
        assertThat(CyberdefTicketWorkflow.isAllowed(OPEN, CLOSED, setOf(OWNER, DEFENDER), null))
            .isEqualTo(CyberdefTicketWorkflow.Check.Allowed(DEFENDER))
        assertThat(CyberdefTicketWorkflow.isAllowed(OPEN, CLOSED, setOf(OWNER, DEFENDER), "fixed"))
            .isEqualTo(CyberdefTicketWorkflow.Check.Allowed(DEFENDER))
        assertThat(CyberdefTicketWorkflow.commentRequiredTargets(OPEN, setOf(OWNER, DEFENDER))).isEmpty()
        assertThat(CyberdefTicketWorkflow.commentRequiredTargets(OPEN, setOf(OWNER))).containsExactly(CLOSED)
    }
}
