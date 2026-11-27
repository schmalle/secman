package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind
import com.secman.domain.CyberdefSeverity
import com.secman.domain.CyberdefTicket
import com.secman.domain.CyberdefTicketStatus
import com.secman.dto.CyberdefTicketDetailDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for status changes, comments and resends; no database, collaborators are mocked. */
class CyberdefTicketServiceTest {
    private val writer = mockk<CyberdefTicketWriter>()
    private val mailer = mockk<CyberdefTicketMailer>(relaxed = true)
    private val access = mockk<CyberdefTicketAccess>()
    private val queries = mockk<CyberdefTicketQueryService>()
    private val service = CyberdefTicketService(writer, mailer, access, queries)

    private fun stored(status: CyberdefTicketStatus = CyberdefTicketStatus.OPEN) = CyberdefTicket(
        id = 10, awsAccountId = "123456789012", title = "t", description = "d", severity = CyberdefSeverity.HIGH,
        status = status, createdByUserId = defender.userId, createdByUsername = "soc", recipientCount = 1
    )

    private fun visible(actor: CyberdefTicketActor, ticket: CyberdefTicket, vararg kinds: CyberdefActorKind) {
        every { access.loadVisible(actor, 10) } returns ticket
        every { access.kinds(actor, ticket) } returns kinds.toSet()
    }

    @Test
    fun `a ticket outside the caller's accounts is not found, not forbidden`() {
        every { access.loadVisible(owner, 10) } returns null
        assertThat(service.transition(owner, 10, "CLOSED", "fixed", 0)).isEqualTo(CyberdefTicketResult.notFound)
        verify(exactly = 0) { writer.transition(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the owner tool cannot act for the defender side even for an admin`() {
        val admin = CyberdefTicketActor(9, "admin", "admin@example.test", setOf("ADMIN"))
        val ticket = stored()
        visible(admin, ticket, CyberdefActorKind.OWNER, CyberdefActorKind.DEFENDER)
        every { writer.transition(admin, 10, setOf(CyberdefActorKind.OWNER), CyberdefTicketStatus.CLOSED, null, 3) } returns
            CyberdefTicketWriter.TransitionOutcome.Denied(CyberdefTicketWorkflow.COMMENT_REQUIRED, "no")
        val result = service.transition(admin, 10, "CLOSED", null, 3, onlyAs = CyberdefActorKind.OWNER)
        assertThat((result as CyberdefTicketResult.Failed).code).isEqualTo(CyberdefTicketWorkflow.COMMENT_REQUIRED)
    }

    @Test
    fun `a successful transition dispatches its mail and returns the new detail`() {
        val ticket = stored()
        val detail = mockk<CyberdefTicketDetailDto>()
        visible(owner, ticket, CyberdefActorKind.OWNER)
        every { writer.transition(owner, 10, setOf(CyberdefActorKind.OWNER), CyberdefTicketStatus.CLOSED, "patched", 4) } returns
            CyberdefTicketWriter.TransitionOutcome.Done(CyberdefTicketWriter.Written(ticket, listOf(77L)))
        every { queries.detailOf(owner, ticket) } returns detail
        assertThat((service.transition(owner, 10, "closed", " patched ", 4) as CyberdefTicketResult.Ok).value).isSameAs(detail)
        verify { mailer.dispatchLater(listOf(77L)) }
    }

    @Test
    fun `a stale version is a conflict`() {
        visible(owner, stored(), CyberdefActorKind.OWNER)
        every { writer.transition(any(), any(), any(), any(), any(), any()) } returns
            CyberdefTicketWriter.TransitionOutcome.Conflict(5, CyberdefTicketStatus.CLOSED)
        val result = service.transition(owner, 10, "CLOSED", "fixed", 4)
        assertThat((result as CyberdefTicketResult.Failed).code).isEqualTo(CyberdefTicketResult.VERSION_CONFLICT)
    }

    @Test
    fun `closed tickets still accept comments but empty comments are invalid`() {
        val closed = stored(CyberdefTicketStatus.CLOSED)
        visible(owner, closed, CyberdefActorKind.OWNER)
        every { writer.comment(owner, 10, CyberdefActorKind.OWNER, "late note") } returns closed
        every { queries.detailOf(owner, closed) } returns mockk()
        assertThat(service.comment(owner, 10, "late note")).isInstanceOf(CyberdefTicketResult.Ok::class.java)
        assertThat((service.comment(owner, 10, "  ") as CyberdefTicketResult.Failed).code).isEqualTo(CyberdefTicketResult.VALIDATION_ERROR)
    }

    @Test
    fun `a status dry run checks the move and names who would be mailed without writing`() {
        val ticket = stored()
        visible(defender, ticket, CyberdefActorKind.DEFENDER)
        every { writer.notifyTargets(ticket, CyberdefActorKind.DEFENDER) } returns listOf("owner@example.test")

        val preview = (service.previewTransition(defender, 10, "CLOSED", null, ticket.version) as CyberdefTicketResult.Ok).value
        assertThat(preview.actingAs).isEqualTo("DEFENDER")
        assertThat(preview.wouldNotify).containsExactly("owner@example.test")
        verify(exactly = 0) { writer.transition(any(), any(), any(), any(), any(), any()) }

        val stale = service.previewTransition(defender, 10, "CLOSED", null, ticket.version + 1)
        assertThat((stale as CyberdefTicketResult.Failed).code).isEqualTo(CyberdefTicketResult.VERSION_CONFLICT)
        val sameStatus = service.previewTransition(defender, 10, "OPEN", "x", null)
        assertThat((sameStatus as CyberdefTicketResult.Failed).code).isEqualTo(CyberdefTicketWorkflow.INVALID_TRANSITION)
    }

    @Test
    fun `only defenders may resend mail`() {
        assertThat(service.resendFailed(champion, 10)).isEqualTo(CyberdefTicketResult.defendersOnly)
    }
}
