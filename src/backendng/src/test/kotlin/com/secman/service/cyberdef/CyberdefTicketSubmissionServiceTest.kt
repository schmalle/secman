package com.secman.service.cyberdef

import com.secman.domain.CyberdefSeverity
import com.secman.domain.CyberdefTicket
import com.secman.repository.CyberdefTicketRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** Unit tests for ticket submission; no database, collaborators are mocked. */
class CyberdefTicketSubmissionServiceTest {
    private val tickets = mockk<CyberdefTicketRepository>()
    private val writer = mockk<CyberdefTicketWriter>()
    private val mailer = mockk<CyberdefTicketMailer>(relaxed = true)
    private val resolver = mockk<CyberdefRecipientResolver>()

    private fun service(maxPerHour: Long = 600) =
        CyberdefTicketSubmissionService(tickets, writer, mailer, resolver, testSettings(maxPerHour = maxPerHour))

    private val routed = CyberdefRouting(listOf(CyberdefRecipient("owner@example.test", 2, "MAPPING")), "prod", false)

    private fun fields(reference: String? = "SOC-1", severity: String = "HIGH", dueAt: String? = null, account: String = "123456789012") =
        CyberdefTicketFields(externalReference = reference, awsAccountId = account, title = "t",
            severity = severity, description = "d", dueAt = dueAt)

    private fun one(f: CyberdefTicketFields = fields(), delivery: CyberdefDelivery? = null) =
        CyberdefTicketCreateRequest(f, delivery)

    private fun stored(id: Long = 10, reference: String? = "SOC-1") = CyberdefTicket(
        id = id, awsAccountId = "123456789012", title = "t", description = "d", severity = CyberdefSeverity.HIGH,
        externalReference = reference, createdByUserId = defender.userId, createdByUsername = "soc", recipientCount = 1
    )

    private fun quiet(existing: List<CyberdefTicket> = emptyList(), createdLastHour: Long = 0) {
        every { tickets.findByCreatedByUserIdAndExternalReferenceIn(defender.userId, any()) } returns existing
        every { tickets.countByCreatedByUserIdAndCreatedAtAfter(defender.userId, any()) } returns createdLastHour
    }

    private fun created(result: CyberdefTicketResult<*>) = (result as CyberdefTicketResult.Ok<*>).value

    @Test
    fun `only defenders may submit`() {
        val result = service().create(owner, one())
        assertThat((result as CyberdefTicketResult.Failed).code).isEqualTo(CyberdefTicketResult.FORBIDDEN)
        verify(exactly = 0) { writer.create(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a new ticket gets the severity SLA, a batch key and its mail after commit`() {
        quiet()
        every { resolver.resolve("123456789012") } returns routed
        val due = slot<Instant>()
        every { writer.create(defender, any(), routed, capture(due), any()) } returns CyberdefTicketWriter.Written(stored(), listOf(5L, 6L))

        val before = Instant.now()
        val result = created(service().create(defender, one())) as com.secman.dto.CyberdefCreateResultDto

        assertThat(result.outcome).isEqualTo(CyberdefTicketSubmissionService.OUTCOME_CREATED)
        assertThat(result.ticketKey).matches("CD-\\d{4}-000010")
        assertThat(due.captured).isBetween(before.plus(Duration.ofDays(7)), Instant.now().plus(Duration.ofDays(7)))
        verify { mailer.dispatchLater(listOf(5L, 6L)) }
    }

    @Test
    fun `a batch reads duplicates, the hourly count and each account's routing once`() {
        quiet(existing = listOf(stored(42, "B")))
        every { resolver.resolve("123456789012") } returns routed
        every { writer.create(any(), any(), any(), any(), any()) } returns CyberdefTicketWriter.Written(stored(), listOf(1L))
        val batch = created(service().createBatch(defender, CyberdefBatchCreateRequest(
            listOf(fields("A"), fields("B"), fields("C"), fields("A"), fields("D", severity = "SEVERE")))))
            as com.secman.dto.CyberdefBatchResultDto

        assertThat(batch.results.map { it.outcome }).containsExactly("CREATED", "DUPLICATE", "CREATED", "DUPLICATE", "REJECTED")
        assertThat(batch.results[4].errors.single().field).isEqualTo("severity")
        verify(exactly = 1) { tickets.findByCreatedByUserIdAndExternalReferenceIn(any(), any()) }
        verify(exactly = 1) { tickets.countByCreatedByUserIdAndCreatedAtAfter(any(), any()) }
        verify(exactly = 1) { resolver.resolve("123456789012") }
        verify(exactly = 1) { mailer.dispatchLater(listOf(1L, 1L)) }
    }

    @Test
    fun `every ticket of one call shares the batch key the digest groups by`() {
        quiet()
        every { resolver.resolve(any()) } returns routed
        val keys = mutableListOf<String?>()
        every { writer.create(any(), any(), any(), any(), captureNullable(keys)) } returns CyberdefTicketWriter.Written(stored(), emptyList())
        service().createBatch(defender, CyberdefBatchCreateRequest(listOf(fields("A"), fields("B"))))
        assertThat(keys).hasSize(2).doesNotContainNull()
        assertThat(keys.distinct()).hasSize(1)
    }

    @Test
    fun `an account without owners is refused unless explicitly allowed`() {
        quiet()
        every { resolver.resolve(any()) } returns CyberdefRouting(emptyList(), null, false)
        val refused = created(service().create(defender, one())) as com.secman.dto.CyberdefCreateResultDto
        assertThat(refused.errorCode).isEqualTo(CyberdefTicketResult.UNROUTABLE_ACCOUNT)
        assertThat(refused.errors.single().field).isEqualTo("awsAccountId")

        every { writer.create(any(), any(), any(), any(), any()) } returns CyberdefTicketWriter.Written(stored(), emptyList())
        val forced = created(service().create(defender, one(delivery = CyberdefDelivery(allowUnrouted = true))))
            as com.secman.dto.CyberdefCreateResultDto
        assertThat(forced.outcome).isEqualTo(CyberdefTicketSubmissionService.OUTCOME_CREATED)
    }

    @Test
    fun `a due date may shorten the SLA but never extend it`() {
        quiet()
        every { resolver.resolve(any()) } returns routed
        val late = Instant.now().plus(Duration.ofDays(30)).toString()
        val result = created(service().create(defender, one(fields(dueAt = late)))) as com.secman.dto.CyberdefCreateResultDto
        assertThat(result.errors.single().field).isEqualTo("dueAt")
        assertThat(result.errors.single().message).contains("7 days")
    }

    @Test
    fun `the hourly budget is spent per stored ticket, not per dry run`() {
        quiet(createdLastHour = 599)
        every { resolver.resolve(any()) } returns routed
        every { writer.create(any(), any(), any(), any(), any()) } returns CyberdefTicketWriter.Written(stored(), emptyList())
        val real = created(service().createBatch(defender, CyberdefBatchCreateRequest(listOf(fields("A"), fields("B")))))
            as com.secman.dto.CyberdefBatchResultDto
        assertThat(real.results.map { it.outcome }).containsExactly("CREATED", "REJECTED")
        assertThat(real.results[1].errorCode).isEqualTo(CyberdefTicketResult.RATE_LIMITED)

        val dry = created(service().createBatch(defender, CyberdefBatchCreateRequest(listOf(fields("A"), fields("B")),
            CyberdefDelivery(dryRun = true)))) as com.secman.dto.CyberdefBatchResultDto
        assertThat(dry.results.map { it.outcome }).containsExactly("WOULD_CREATE", "WOULD_CREATE")
    }

    @Test
    fun `a dry run resolves everything but stores and mails nothing`() {
        quiet()
        every { resolver.resolve("123456789012") } returns routed
        val result = created(service().create(defender, one(delivery = CyberdefDelivery(dryRun = true))))
            as com.secman.dto.CyberdefCreateResultDto
        assertThat(result.outcome).isEqualTo(CyberdefTicketSubmissionService.OUTCOME_WOULD_CREATE)
        assertThat(result.recipients).containsExactly("owner@example.test")
        assertThat(result.dueAt).isNotNull()
        verify(exactly = 0) { writer.create(any(), any(), any(), any(), any()); mailer.dispatchLater(any()) }
    }

    @Test
    fun `named recipients replace the owners and refused ones are named by field`() {
        quiet()
        val overrideRouting = CyberdefRouting(listOf(CyberdefRecipient("analyst@soc.example", null, "OVERRIDE")), "prod", false)
        every { resolver.checkOverride(listOf("analyst@soc.example")) } returns
            CyberdefRecipientResolver.Override.Accepted(overrideRouting.recipients)
        every { resolver.accountName("123456789012") } returns "prod"
        every { writer.create(defender, any(), overrideRouting, any(), any()) } returns CyberdefTicketWriter.Written(stored(), emptyList())
        val accepted = created(service().create(defender, one(delivery = CyberdefDelivery(recipients = listOf(" Analyst@SOC.example ")))))
            as com.secman.dto.CyberdefCreateResultDto
        assertThat(accepted.recipientsOverridden).isTrue()
        verify(exactly = 0) { resolver.resolve(any()) }

        every { resolver.checkOverride(listOf("ok@soc.example", "x@evil.example")) } returns
            CyberdefRecipientResolver.Override.Refused(listOf("x@evil.example"))
        val refused = created(service().create(defender, one(fields("SOC-2"),
            CyberdefDelivery(recipients = listOf("ok@soc.example", "x@evil.example"))))) as com.secman.dto.CyberdefCreateResultDto
        assertThat(refused.errorCode).isEqualTo(CyberdefTicketResult.RECIPIENT_NOT_ALLOWED)
        assertThat(refused.errors.single().field).isEqualTo("delivery.recipients[1]")
    }

    @Test
    fun `invalid delivery options or batch size fail the whole call with field errors`() {
        val badDelivery = service().createBatch(defender, CyberdefBatchCreateRequest(listOf(fields()),
            CyberdefDelivery(recipients = listOf("nope"))))
        assertThat((badDelivery as CyberdefTicketResult.Failed).fieldErrors.single().field).isEqualTo("delivery.recipients[0]")
        val tooMany = service().createBatch(defender, CyberdefBatchCreateRequest(List(101) { fields("R$it") }))
        assertThat((tooMany as CyberdefTicketResult.Failed).code).isEqualTo(CyberdefTicketResult.VALIDATION_ERROR)
    }

    @Test
    fun `a batch over several accounts checks the override list once`() {
        quiet()
        every { resolver.checkOverride(any()) } returns
            CyberdefRecipientResolver.Override.Accepted(listOf(CyberdefRecipient("a@soc.example", null, "OVERRIDE")))
        every { resolver.accountName(any()) } returns null
        every { writer.create(any(), any(), any(), any(), any()) } returns CyberdefTicketWriter.Written(stored(), emptyList())
        service().createBatch(defender, CyberdefBatchCreateRequest(
            listOf(fields("A", account = "111111111111"), fields("B", account = "222222222222"), fields("C", account = "111111111111")),
            CyberdefDelivery(recipients = listOf("a@soc.example"))))
        verify(exactly = 1) { resolver.checkOverride(any()) }
        verify(exactly = 1) { resolver.accountName("111111111111") }
        verify(exactly = 1) { resolver.accountName("222222222222") }
    }
}
