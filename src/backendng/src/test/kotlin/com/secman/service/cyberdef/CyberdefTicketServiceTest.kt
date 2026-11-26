package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind
import com.secman.domain.CyberdefSeverity
import com.secman.domain.CyberdefTicket
import com.secman.domain.CyberdefTicketStatus
import com.secman.dto.CyberdefTicketDetailDto
import com.secman.repository.CyberdefTicketRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.ExecutorService

/** Unit tests for CyberdefTicketService; no database, collaborators are mocked. */
class CyberdefTicketServiceTest {
    private val tickets = mockk<CyberdefTicketRepository>()
    private val writer = mockk<CyberdefTicketWriter>()
    private val mailer = mockk<CyberdefTicketMailer>(relaxed = true)
    private val resolver = mockk<CyberdefRecipientResolver>()
    private val access = mockk<CyberdefTicketAccess>()
    private val queries = mockk<CyberdefTicketQueryService>()
    private val executor = mockk<ExecutorService>().also {
        every { it.execute(any()) } answers { firstArg<Runnable>().run() }
    }
    private val service = CyberdefTicketService(tickets, writer, mailer, resolver, access, queries, testSettings(), executor)

    private val routed = CyberdefRouting(listOf(CyberdefRecipient("owner@example.test", 2, "MAPPING")), "prod", false)

    private fun request(reference: String? = "SOC-1", severity: String = "HIGH", dueAt: String? = null, allowUnrouted: Boolean? = null) =
        CyberdefTicketCreateRequest(externalReference = reference, awsAccountId = "123456789012", title = "t",
            severity = severity, description = "d", dueAt = dueAt, allowUnrouted = allowUnrouted)

    private fun stored(id: Long = 10, status: CyberdefTicketStatus = CyberdefTicketStatus.NEW) = CyberdefTicket(
        id = id, ticketKey = "CD-2026-%06d".format(id), awsAccountId = "123456789012", title = "t", description = "d",
        severity = CyberdefSeverity.HIGH, status = status, createdByUserId = defender.userId, createdByUsername = "soc",
        recipientCount = 1
    )

    private fun noDuplicates() {
        every { tickets.findByCreatedByUserIdAndExternalReference(any(), any()) } returns Optional.empty()
        every { tickets.countByCreatedByUserIdAndCreatedAtAfter(any(), any()) } returns 0L
    }

    @Test
    fun `only defenders may submit`() {
        val result = service.create(owner, request())
        assertThat((result as CyberdefTicketResult.Failed).code).isEqualTo(CyberdefTicketResult.FORBIDDEN)
        verify(exactly = 0) { writer.create(any(), any(), any(), any()) }
    }

    @Test
    fun `a new ticket gets the severity SLA and its mail is dispatched after commit`() {
        noDuplicates()
        every { resolver.resolve("123456789012") } returns routed
        val due = slot<Instant>()
        every { writer.create(defender, any(), routed, capture(due)) } returns CyberdefTicketWriter.Written(stored(), listOf(5L, 6L))

        val before = Instant.now()
        val result = (service.create(defender, request()) as CyberdefTicketResult.Ok).value

        assertThat(result.outcome).isEqualTo(CyberdefTicketService.OUTCOME_CREATED)
        assertThat(result.ticketKey).isEqualTo("CD-2026-000010")
        assertThat(due.captured).isBetween(before.plus(Duration.ofDays(7)), Instant.now().plus(Duration.ofDays(7)))
        verify { mailer.dispatch(listOf(5L, 6L)) }
    }

    @Test
    fun `resending an external reference returns the existing ticket without writing or mailing`() {
        every { tickets.findByCreatedByUserIdAndExternalReference(defender.userId, "SOC-1") } returns Optional.of(stored(42))
        val result = (service.create(defender, request()) as CyberdefTicketResult.Ok).value
        assertThat(result.outcome).isEqualTo(CyberdefTicketService.OUTCOME_DUPLICATE)
        assertThat(result.ticketId).isEqualTo(42)
        verify(exactly = 0) { writer.create(any(), any(), any(), any()); mailer.dispatch(any()) }
    }

    @Test
    fun `an account without owners is refused unless explicitly allowed`() {
        noDuplicates()
        every { resolver.resolve(any()) } returns CyberdefRouting(emptyList(), null, false)
        val refused = (service.create(defender, request()) as CyberdefTicketResult.Ok).value
        assertThat(refused.outcome).isEqualTo(CyberdefTicketService.OUTCOME_REJECTED)
        assertThat(refused.errorCode).isEqualTo(CyberdefTicketResult.UNROUTABLE_ACCOUNT)

        every { writer.create(any(), any(), any(), any()) } returns CyberdefTicketWriter.Written(stored(), emptyList())
        val forced = (service.create(defender, request(reference = "SOC-2", allowUnrouted = true)) as CyberdefTicketResult.Ok).value
        assertThat(forced.outcome).isEqualTo(CyberdefTicketService.OUTCOME_CREATED)
    }

    @Test
    fun `a due date may shorten the SLA but never extend it`() {
        noDuplicates()
        every { resolver.resolve(any()) } returns routed
        val late = Instant.now().plus(Duration.ofDays(30)).toString()
        val result = (service.create(defender, request(dueAt = late)) as CyberdefTicketResult.Ok).value
        assertThat(result.errorCode).isEqualTo(CyberdefTicketResult.VALIDATION_ERROR)
        assertThat(result.errors.single()).contains("7 days")
    }

    @Test
    fun `submissions above the hourly limit are refused`() {
        every { tickets.findByCreatedByUserIdAndExternalReference(any(), any()) } returns Optional.empty()
        every { tickets.countByCreatedByUserIdAndCreatedAtAfter(defender.userId, any()) } returns 600L
        val result = (service.create(defender, request()) as CyberdefTicketResult.Ok).value
        assertThat(result.errorCode).isEqualTo(CyberdefTicketResult.RATE_LIMITED)
    }

    @Test
    fun `a batch reports each item and one bad item does not stop the rest`() {
        noDuplicates()
        every { resolver.resolve(any()) } returns routed
        every { writer.create(any(), any(), any(), any()) } returns CyberdefTicketWriter.Written(stored(), emptyList())
        val batch = (service.createBatch(defender, listOf(request("A"), request("B", severity = "SEVERE"), request("C")))
            as CyberdefTicketResult.Ok).value
        assertThat(batch.created).isEqualTo(2)
        assertThat(batch.rejected).isEqualTo(1)
        assertThat(batch.results.map { it.index to it.outcome }).containsExactly(
            0 to "CREATED", 1 to "REJECTED", 2 to "CREATED")
    }

    @Test
    fun `a batch over 100 items is refused as a whole`() {
        val result = service.createBatch(defender, List(101) { request("R$it") })
        assertThat((result as CyberdefTicketResult.Failed).code).isEqualTo(CyberdefTicketResult.VALIDATION_ERROR)
    }

    @Test
    fun `a ticket outside the caller's accounts is not found, not forbidden`() {
        every { access.loadVisible(owner, 10) } returns null
        val result = service.transition(owner, 10, "ACKNOWLEDGED", null, 0)
        assertThat(result).isEqualTo(CyberdefTicketResult.notFound)
        verify(exactly = 0) { writer.transition(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the owner tool cannot act for the defender side even for an admin`() {
        val admin = CyberdefTicketActor(9, "admin", "admin@example.test", setOf("ADMIN"))
        val ticket = stored(status = CyberdefTicketStatus.RESOLVED)
        every { access.loadVisible(admin, 10) } returns ticket
        every { access.kinds(admin, ticket) } returns setOf(CyberdefActorKind.OWNER, CyberdefActorKind.DEFENDER)
        every { writer.transition(admin, 10, setOf(CyberdefActorKind.OWNER), CyberdefTicketStatus.CLOSED, null, 3) } returns
            CyberdefTicketWriter.TransitionOutcome.Denied(CyberdefTicketWorkflow.INVALID_TRANSITION, "no")

        val result = service.transition(admin, 10, "CLOSED", null, 3, onlyAs = CyberdefActorKind.OWNER)

        assertThat((result as CyberdefTicketResult.Failed).code).isEqualTo(CyberdefTicketWorkflow.INVALID_TRANSITION)
    }

    @Test
    fun `a successful transition dispatches its mail and returns the new detail`() {
        val ticket = stored(status = CyberdefTicketStatus.IN_PROGRESS)
        val detail = mockk<CyberdefTicketDetailDto>()
        every { access.loadVisible(owner, 10) } returns ticket
        every { access.kinds(owner, ticket) } returns setOf(CyberdefActorKind.OWNER)
        every { writer.transition(owner, 10, setOf(CyberdefActorKind.OWNER), CyberdefTicketStatus.RESOLVED, "patched", 4) } returns
            CyberdefTicketWriter.TransitionOutcome.Done(CyberdefTicketWriter.Written(ticket, listOf(77L)))
        every { queries.detailOf(owner, ticket) } returns detail

        val result = service.transition(owner, 10, "resolved", " patched ", 4)

        assertThat((result as CyberdefTicketResult.Ok).value).isSameAs(detail)
        verify { mailer.dispatch(listOf(77L)) }
    }

    @Test
    fun `a stale version is a conflict`() {
        val ticket = stored()
        every { access.loadVisible(owner, 10) } returns ticket
        every { access.kinds(owner, ticket) } returns setOf(CyberdefActorKind.OWNER)
        every { writer.transition(any(), any(), any(), any(), any(), any()) } returns
            CyberdefTicketWriter.TransitionOutcome.Conflict(5, CyberdefTicketStatus.ACKNOWLEDGED)
        val result = service.transition(owner, 10, "ACKNOWLEDGED", null, 4)
        assertThat((result as CyberdefTicketResult.Failed).code).isEqualTo(CyberdefTicketResult.VERSION_CONFLICT)
    }

    @Test
    fun `comments on closed tickets are refused and empty comments are invalid`() {
        val closed = stored(status = CyberdefTicketStatus.CLOSED)
        every { access.loadVisible(owner, 10) } returns closed
        every { access.kinds(owner, closed) } returns setOf(CyberdefActorKind.OWNER)
        assertThat((service.comment(owner, 10, "late") as CyberdefTicketResult.Failed).code).isEqualTo(CyberdefTicketResult.FORBIDDEN)
        assertThat((service.comment(owner, 10, "  ") as CyberdefTicketResult.Failed).code).isEqualTo(CyberdefTicketResult.VALIDATION_ERROR)
    }

    @Test
    fun `only defenders may resend mail`() {
        assertThat(service.resendFailed(champion, 10)).isEqualTo(CyberdefTicketResult.defendersOnly)
    }
}
