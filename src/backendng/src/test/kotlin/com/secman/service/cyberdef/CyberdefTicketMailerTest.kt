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
import java.util.Optional

/** Unit tests for CyberdefTicketMailer; no database, collaborators are mocked. */
class CyberdefTicketMailerTest {
    private val notifications = mockk<CyberdefTicketNotificationRepository>(relaxed = true)
    private val tickets = mockk<CyberdefTicketRepository>()
    private val events = mockk<CyberdefTicketEventRepository>()
    private val email = mockk<EmailService>()
    private val templates = EmailTemplateRenderer()

    private val ticket = CyberdefTicket(
        id = 10, ticketKey = "CD-2026-000010", awsAccountId = "123456789012", awsAccountName = "prod",
        title = "Exposed <b>snapshot</b> {ticketUrl}", description = "SECRET-DETAIL", severity = CyberdefSeverity.HIGH,
        createdByUserId = 1, createdByUsername = "soc"
    )
    private val row = CyberdefTicketNotification(id = 5, ticketId = 10, recipientEmail = "owner@example.test",
        kind = CyberdefTicketWriter.KIND_CREATED, eventKey = "k")

    private fun mailer(includeSummary: Boolean = false) =
        CyberdefTicketMailer(notifications, tickets, events, email, templates, testSettings(includeSummary = includeSummary))

    private fun stubRow() {
        every { notifications.findById(5) } returns Optional.of(row)
        every { tickets.findById(10) } returns Optional.of(ticket)
    }

    @Test
    fun `a row another dispatcher claimed is not sent again`() {
        every { notifications.claim(5, CyberdefTicketNotification.STATUS_PENDING, any()) } returns 0
        val tally = mailer().dispatch(listOf(5L))
        assertThat(tally.attempted).isZero()
        verify(exactly = 0) { email.sendTrackedEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the default mail names the ticket and links to it but keeps details behind the login`() {
        stubRow()
        every { notifications.claim(5, CyberdefTicketNotification.STATUS_PENDING, any()) } returns 1
        val subject = slot<String>()
        val text = slot<String>()
        val html = slot<String>()
        every { email.sendTrackedEmailWithInlineImages("owner@example.test", capture(subject), capture(text), capture(html), any()) } returns
            EmailService.TrackedDelivery("SENT", messageId = "m")

        val tally = mailer().dispatch(listOf(5L))

        assertThat(tally.sent).isEqualTo(1)
        assertThat(subject.captured).contains("CD-2026-000010", "123456789012").doesNotContain("\n")
        assertThat(text.captured).contains("https://secman.example.test/security-tickets/10", "prod (123456789012)")
        assertThat(text.captured + html.captured).doesNotContain("SECRET-DETAIL")
        assertThat(html.captured).contains("&lt;b&gt;snapshot&lt;/b&gt;").doesNotContain("<b>snapshot")
        // A placeholder inside submitter text stays literal.
        assertThat(text.captured).contains("snapshot</b> {ticketUrl}")
        verify { notifications.finish(5, CyberdefTicketNotification.STATUS_SENT, null, any(), any()) }
    }

    @Test
    fun `the summary appears only when the operator enables it`() {
        stubRow()
        every { notifications.claim(5, any(), any()) } returns 1
        val text = slot<String>()
        every { email.sendTrackedEmailWithInlineImages(any(), any(), capture(text), any(), any()) } returns EmailService.TrackedDelivery("SENT")
        mailer(includeSummary = true).dispatch(listOf(5L))
        assertThat(text.captured).contains("SECRET-DETAIL")
    }

    @Test
    fun `a transport failure is recorded as uncertain and never retried automatically`() {
        stubRow()
        every { notifications.claim(5, any(), any()) } returns 1
        every { email.sendTrackedEmailWithInlineImages(any(), any(), any(), any(), any()) } throws RuntimeException("boom")
        val tally = mailer().dispatch(listOf(5L))
        assertThat(tally.failed).isEqualTo(1)
        verify { notifications.finish(5, CyberdefTicketNotification.STATUS_UNCERTAIN, "DELIVERY_UNCERTAIN", any(), null) }
    }

    @Test
    fun `resend only claims rows that failed cleanly`() {
        every { notifications.findByTicketIdAndStatus(10, CyberdefTicketNotification.STATUS_FAILED) } returns listOf(row)
        every { notifications.claim(5, CyberdefTicketNotification.STATUS_FAILED, any()) } returns 0
        mailer().resendFailed(10)
        verify { notifications.claim(5, CyberdefTicketNotification.STATUS_FAILED, any()) }
    }
}
