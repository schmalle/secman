package com.secman.service

import com.secman.config.AppConfig
import com.secman.config.BackendConfig
import com.secman.domain.AwsAccountSharingCreatedEvent
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class AwsAccountSharingNotificationServiceTest {

    private lateinit var emailService: EmailService
    private lateinit var appConfig: AppConfig
    private lateinit var service: AwsAccountSharingNotificationService

    private fun sampleEvent() = AwsAccountSharingCreatedEvent(
        sharingId = 42L,
        sourceUserEmail = "alice@example.com",
        targetUserId = 7L,
        targetUserEmail = "bob@example.com",
        targetUsername = "bob",
        createdByEmail = "admin@example.com",
        createdAtIso = "2026-05-06T10:15:00Z",
        sharedAwsAccountCount = 3,
    )

    @BeforeEach
    fun setUp() {
        emailService = mockk(relaxed = false)
        appConfig = mockk()
        val backendCfg = mockk<BackendConfig>()
        every { backendCfg.baseUrl } returns "https://secman.example.com/"
        every { appConfig.backend } returns backendCfg
        service = AwsAccountSharingNotificationService(emailService, appConfig)
    }

    @Test
    fun `sends email to target user with substituted fields`() {
        val toSlot = slot<String>()
        val subjectSlot = slot<String>()
        val textSlot = slot<String>()
        val htmlSlot = slot<String>()
        every {
            emailService.sendEmailWithInlineImages(
                capture(toSlot), capture(subjectSlot), capture(textSlot), capture(htmlSlot), any()
            )
        } returns CompletableFuture.completedFuture(true)

        service.notifyTargetOfNewShare(sampleEvent()).get()

        assert(toSlot.captured == "bob@example.com")
        assert(subjectSlot.captured == "AWS account access shared with you in SecMan")

        // Both bodies must contain key fields
        for (body in listOf(textSlot.captured, htmlSlot.captured)) {
            assert(body.contains("alice@example.com")) { "missing source email in: $body" }
            assert(body.contains("bob")) { "missing target username in: $body" }
            assert(body.contains("3")) { "missing account count in: $body" }
            assert(body.contains("admin@example.com")) { "missing createdBy email in: $body" }
            assert(body.contains("2026-05-06T10:15:00Z")) { "missing createdAtIso in: $body" }
            // baseUrl trailing slash is trimmed
            assert(body.contains("https://secman.example.com/assets")) { "missing assets url in: $body" }
        }

        verify(exactly = 1) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `returns false when emailService is not configured`() {
        every {
            emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(false)

        val result = service.notifyTargetOfNewShare(sampleEvent()).get()

        assertFalse(result)
    }

    @Test
    fun `does not throw when emailService throws`() {
        every {
            emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any())
        } throws RuntimeException("smtp blew up")

        // Must not propagate; CompletableFuture should resolve to false
        val result = service.notifyTargetOfNewShare(sampleEvent()).get()
        assertFalse(result)
    }
}
