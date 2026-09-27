package com.secman.service

import com.secman.config.AppConfig
import com.secman.domain.OwnerMailNotification
import com.secman.repository.OwnerMailNotificationRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Optional

class OwnerMailDeliveryServiceTest {
    private val repository = mockk<OwnerMailNotificationRepository>(relaxed = true)
    private val email = mockk<EmailService>()
    private val renderer = mockk<EmailTemplateRenderer>(relaxed = true)
    private val releases = mockk<ReleaseRequirementScopeService>()
    private val service = OwnerMailDeliveryService(repository, email, renderer, releases, AppConfig(), "account-welcome")

    @Test fun `normalized owner key is stable across case and whitespace`() {
        assertEquals(OwnerMailDeliveryService.eventKey("123456789012", " Owner@Example.test "),
            OwnerMailDeliveryService.eventKey("123456789012", "owner@example.test"))
        assertNotEquals(OwnerMailDeliveryService.eventKey("123456789013", "owner@example.test"),
            OwnerMailDeliveryService.eventKey("123456789012", "owner@example.test"))
    }

    @Test fun `provider outcomes persist independently of import success`() {
        val row = OwnerMailNotification(id = 42, eventKey = "key", awsAccountId = "123456789012", ownerEmail = "owner@example.test")
        every { repository.findByEventKey(any()) } returns Optional.of(row)
        every { repository.claim(42, false, any(), any()) } returns 1
        every { releases.findActiveRelease() } returns null
        every { renderer.requireAllowed(any()) } returns "account-welcome"
        for (result in listOf(EmailService.TrackedDelivery("SENT", messageId = "accepted"),
            EmailService.TrackedDelivery("FAILED", "NO_ACTIVE_PROVIDER"),
            EmailService.TrackedDelivery("PENDING", "DELIVERY_UNCERTAIN"))) {
            every { email.sendTrackedEmailWithInlineImages(any(), any(), any(), any(), any()) } returns result
            val delivery = service.welcome("123456789012", "OWNER@example.test", true, false)
            assertEquals(result.status, delivery.status)
            assertEquals(result.status == "FAILED", delivery.retryable)
            verify { repository.finish(42, result.status, result.errorCode, result.messageId, any()) }
        }
    }

    @Test fun `lost claim and sent replay do not call mail provider`() {
        val row = OwnerMailNotification(id = 42, status = "SENT")
        every { repository.findByEventKey(any()) } returns Optional.of(row)
        every { repository.findById(42) } returns Optional.of(row)
        every { repository.claim(any(), any(), any(), any()) } returns 0
        assertEquals("SENT", service.welcome("123456789012", "owner@example.test", true, false).status)
        assertThrows(IllegalStateException::class.java) { service.retry(42, 1) }
        verify { email wasNot Called }
    }
    @Test fun `missing provider is a definite retryable failure without attempting transport`() {
        val config = mockk<com.secman.repository.EmailConfigRepository>()
        every { config.findActiveConfig() } returns Optional.empty()
        val result = EmailService(config).sendTrackedEmailWithInlineImages("owner@example.test", "Welcome", "Text", "<p>Text</p>", emptyMap())
        assertEquals("FAILED", result.status)
        assertEquals("NO_ACTIVE_PROVIDER", result.errorCode)
    }

    @Test fun `explicit retry of a failed record claims it before sending`() {
        val row = OwnerMailNotification(id = 42, status = "FAILED", awsAccountId = "123456789012", ownerEmail = "owner@example.test")
        every { repository.findById(42) } returns Optional.of(row)
        every { repository.claim(42, true, any(), any()) } returns 1
        every { releases.findActiveRelease() } returns null
        every { renderer.requireAllowed(any()) } returns "account-welcome"
        every { email.sendTrackedEmailWithInlineImages(any(), any(), any(), any(), any()) } returns EmailService.TrackedDelivery("SENT")
        assertEquals("SENT", service.retry(42, 7).status)
        verifyOrder {
            repository.claim(42, true, any(), any())
            email.sendTrackedEmailWithInlineImages(any(), any(), any(), any(), any())
            repository.finish(42, "SENT", null, null, any())
        }
    }

}
