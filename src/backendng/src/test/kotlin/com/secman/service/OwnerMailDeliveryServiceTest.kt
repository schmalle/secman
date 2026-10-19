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

    @Test fun `dry run and opt out do not reserve a delivery or contact the provider`() {
        val preview = service.welcome("123456789012", " OWNER@example.test ", true, true)
        assertEquals("WOULD_SEND", preview.status)
        assertNull(preview.notificationId)
        assertEquals("SKIPPED", service.welcome("123456789012", "owner@example.test", false, false).status)
        verify { repository wasNot Called; email wasNot Called; renderer wasNot Called; releases wasNot Called }
    }

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

    @Test fun `group keys are order independent and single account keys stay compatible`() {
        val ids = listOf("111111111111", "222222222222")
        assertEquals(OwnerMailDeliveryService.eventKey(ids, " OWNER@example.test "),
            OwnerMailDeliveryService.eventKey(ids.reversed() + ids.first(), "owner@example.test"))
        assertEquals(OwnerMailDeliveryService.eventKey(ids.first(), "owner@example.test"),
            OwnerMailDeliveryService.eventKey(listOf(ids.first()), "owner@example.test"))
        assertNotEquals(OwnerMailDeliveryService.eventKey(ids, "owner@example.test"),
            OwnerMailDeliveryService.eventKey(ids, "other@example.test"))
    }

    @Test fun `grouped delivery persists all accounts and retry sends the same account list`() {
        val ids = listOf("111111111111", "222222222222")
        val row = OwnerMailNotification(id = 42, awsAccountId = ids.first(),
            awsAccountIds = ids.joinToString(","), ownerEmail = "owner@example.test", status = "FAILED")
        every { repository.findByEventKey(any()) } returns Optional.of(row)
        every { repository.findById(42) } returns Optional.of(row)
        every { repository.claim(42, any(), any(), any()) } returns 1
        every { releases.findActiveRelease() } returns null
        every { renderer.requireAllowed(any()) } returns "account-welcome"
        every { renderer.escapeHtml(any()) } answers { firstArg() }
        every { renderer.render(any(), any(), any()) } answers { if (thirdArg<Boolean>()) "<html><body>Welcome</body></html>" else "Welcome" }
        every { email.sendTrackedEmailWithInlineImages(any(), any(), any(), any(), any()) } returnsMany
            listOf(EmailService.TrackedDelivery("FAILED", "NO_ACTIVE_PROVIDER"), EmailService.TrackedDelivery("SENT"))
        assertEquals(ids, service.welcome(ids.reversed(), "OWNER@example.test", true, false).accountIds)
        assertEquals(ids, service.retry(42, 7).accountIds)
        verify(exactly = 1) { repository.reserveGroup(any(), ids.first(), ids.joinToString(","), "owner@example.test", any()) }
        verify(exactly = 2) { email.sendTrackedEmailWithInlineImages("owner@example.test", any(),
            match { ids.all(it::contains) }, match { html -> ids.all { html.indexOf(it) in 0 until html.indexOf("</body>") } }, any()) }
    }

    @Test fun `grouped dry run does not reserve or send and rejects malformed account IDs`() {
        val ids = listOf("111111111111", "222222222222")
        assertEquals(ids, service.welcome(ids, "owner@example.test", true, true).accountIds)
        assertThrows(IllegalArgumentException::class.java) {
            service.welcome(ids + "<script>", "owner@example.test", true, true)
        }
        verify { repository wasNot Called; email wasNot Called }
    }

    @Test fun `test mail uses grouped renderer and marks both bodies without reserving production delivery`() {
        val ids = listOf("111111111111", "222222222222")
        every { releases.findActiveRelease() } returns null
        every { renderer.requireAllowed(any()) } returns "account-welcome"
        every { renderer.escapeHtml(any()) } answers { firstArg() }
        every { renderer.render(any(), any(), any()) } answers {
            if (thirdArg<Boolean>()) "<html><body>Welcome</body></html>" else "Welcome"
        }
        every { email.sendTrackedEmailWithInlineImages(any(), any(), any(), any(), any()) } returns EmailService.TrackedDelivery("SENT")
        repeat(2) {
            val outcome = service.testWelcome(ids.reversed(), " TEST@example.test ", "admin")
            assertEquals("SENT", outcome.status)
            assertEquals(ids, outcome.accountIds)
            assertNull(outcome.notificationId)
            assertFalse(outcome.retryable)
        }
        verify(exactly = 2) { email.sendTrackedEmailWithInlineImages("test@example.test",
            match { it.startsWith("[TEST] ") }, match { it.contains("requested by admin") && ids.all(it::contains) },
            match { it.contains("requested by admin") && ids.all(it::contains) }, any()) }
        verify { repository wasNot Called }
    }

    @Test fun `test delivery failure and uncertainty are reported without production retry state`() {
        every { releases.findActiveRelease() } returns null
        every { renderer.requireAllowed(any()) } returns "account-welcome"
        for (status in listOf("FAILED", "PENDING")) {
            every { email.sendTrackedEmailWithInlineImages(any(), any(), any(), any(), any()) } returns EmailService.TrackedDelivery(status)
            val result = service.testWelcome(listOf("111111111111"), "test@example.test", "admin")
            assertEquals(status, result.status)
            assertFalse(result.retryable)
            assertNull(result.notificationId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.testWelcome(listOf("111111111111"), "one@example.test,two@example.test", "admin")
        }
        verify { repository wasNot Called }
    }

}
