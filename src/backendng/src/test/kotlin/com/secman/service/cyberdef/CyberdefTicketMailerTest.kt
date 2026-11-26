package com.secman.service.cyberdef

import com.secman.domain.CyberdefSeverity
import com.secman.domain.CyberdefTicket
import com.secman.domain.CyberdefTicketNotification
import com.secman.repository.CyberdefTicketEventRepository
import com.secman.repository.CyberdefTicketNotificationRepository
import com.secman.repository.CyberdefTicketRepository
import com.secman.service.EmailService
import com.secman.service.EmailTemplateRenderer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutorService

/** Unit tests for the mail outbox dispatcher and its renderer; no database, collaborators are mocked. */
class CyberdefTicketMailerTest {
    private val notifications = mockk<CyberdefTicketNotificationRepository>(relaxed = true)
    private val tickets = mockk<CyberdefTicketRepository>()
    private val events = mockk<CyberdefTicketEventRepository>()
    private val email = mockk<EmailService>()
    private val templates = EmailTemplateRenderer()

    private fun ticket(id: Long, title: String = "Exposed <b>snapshot</b> {ticketUrl}", severity: CyberdefSeverity = CyberdefSeverity.HIGH) =
        CyberdefTicket(id = id, awsAccountId = "123456789012", awsAccountName = "prod", title = title,
            description = "SECRET-DETAIL", severity = severity, createdByUserId = 1, createdByUsername = "soc")

    private fun row(id: Long, ticketId: Long, batch: String? = null, recipient: String = "owner@example.test") =
        CyberdefTicketNotification(id = id, ticketId = ticketId, recipientEmail = recipient,
            kind = CyberdefTicketWriter.KIND_CREATED, batchKey = batch, eventKey = "k$id")

    private fun mailer(includeSummary: Boolean = false) = CyberdefTicketMailer(notifications, tickets, events, email, templates,
        CyberdefTicketMailRenderer(templates, testSettings(includeSummary = includeSummary)), mockk<ExecutorService>())

    private fun stub(rows: List<CyberdefTicketNotification>, vararg stored: CyberdefTicket) {
        every { notifications.findByIdIn(any()) } returns rows
        every { tickets.findByIdIn(any()) } returns stored.toList()
        every { notifications.claim(any(), any(), any()) } returns 1
    }

    @Test
    fun `a row another dispatcher claimed is not sent again`() {
        stub(listOf(row(5, 10)), ticket(10))
        every { notifications.claim(5, CyberdefTicketNotification.STATUS_PENDING, any()) } returns 0
        assertThat(mailer().dispatch(listOf(5L)).attempted).isZero()
        verify(exactly = 0) { email.sendTrackedEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the default mail links to the ticket but keeps details behind the login`() {
        stub(listOf(row(5, 10)), ticket(10))
        val subject = slot<String>()
        val text = slot<String>()
        val html = slot<String>()
        every { email.sendTrackedEmailWithInlineImages("owner@example.test", capture(subject), capture(text), capture(html), any()) } returns
            EmailService.TrackedDelivery("SENT", messageId = "m")

        assertThat(mailer().dispatch(listOf(5L)).sent).isEqualTo(1)

        assertThat(subject.captured).contains("CD-", "123456789012").doesNotContain("\n")
        assertThat(text.captured).contains("https://secman.example.test/security-tickets/10", "prod (123456789012)")
        assertThat(text.captured + html.captured).doesNotContain("SECRET-DETAIL")
        assertThat(html.captured).contains("&lt;b&gt;snapshot&lt;/b&gt;").doesNotContain("<b>snapshot")
        assertThat(text.captured).contains("snapshot</b> {ticketUrl}")
        verify { notifications.finishAll(listOf(5L), CyberdefTicketNotification.STATUS_SENT, null, any(), any()) }
    }

    @Test
    fun `creation mails of one batch to one recipient become a single digest`() {
        stub(listOf(row(1, 10, "b1"), row(2, 11, "b1"), row(3, 12, "b1"), row(4, 10, "b1", "other@example.test")),
            ticket(10, "Low thing", CyberdefSeverity.LOW), ticket(11, "<script>x</script>", CyberdefSeverity.CRITICAL), ticket(12, "Mid"))
        val subjects = mutableListOf<String>()
        val htmls = mutableListOf<String>()
        every { email.sendTrackedEmailWithInlineImages(any(), capture(subjects), any(), capture(htmls), any()) } returns
            EmailService.TrackedDelivery("SENT")

        val tally = mailer().dispatch(listOf(1L, 2L, 3L, 4L))

        assertThat(tally.sent).isEqualTo(2)
        verify(exactly = 1) { email.sendTrackedEmailWithInlineImages("owner@example.test", any(), any(), any(), any()) }
        assertThat(subjects).anyMatch { it.startsWith("[CRITICAL] 3 new security tickets") }
        val digest = htmls.first { it.contains("3 new security tickets") }
        assertThat(digest).contains("&lt;script&gt;").doesNotContain("<script>")
        assertThat(digest.indexOf("CD-")).isLessThan(digest.indexOf("Low thing"))
        verify { notifications.finishAll(listOf(1L, 2L, 3L), CyberdefTicketNotification.STATUS_SENT, null, any(), any()) }
    }

    @Test
    fun `the summary appears only when the operator enables it`() {
        stub(listOf(row(5, 10)), ticket(10))
        val text = slot<String>()
        every { email.sendTrackedEmailWithInlineImages(any(), any(), capture(text), any(), any()) } returns EmailService.TrackedDelivery("SENT")
        mailer(includeSummary = true).dispatch(listOf(5L))
        assertThat(text.captured).contains("SECRET-DETAIL")
    }

    @Test
    fun `a transport failure is recorded as uncertain and never retried automatically`() {
        stub(listOf(row(5, 10)), ticket(10))
        every { email.sendTrackedEmailWithInlineImages(any(), any(), any(), any(), any()) } throws RuntimeException("boom")
        assertThat(mailer().dispatch(listOf(5L)).failed).isEqualTo(1)
        verify { notifications.finishAll(listOf(5L), CyberdefTicketNotification.STATUS_UNCERTAIN, "DELIVERY_UNCERTAIN", any(), null) }
    }

    @Test
    fun `resend only considers rows that failed cleanly`() {
        val failed = row(5, 10).apply { status = CyberdefTicketNotification.STATUS_FAILED }
        every { notifications.findByTicketIdAndStatus(10, CyberdefTicketNotification.STATUS_FAILED) } returns listOf(failed)
        stub(listOf(failed), ticket(10))
        every { notifications.claim(5, CyberdefTicketNotification.STATUS_FAILED, any()) } returns 0
        mailer().resendFailed(10)
        verify { notifications.claim(5, CyberdefTicketNotification.STATUS_FAILED, any()) }
    }
}
