package com.secman.service

import com.secman.domain.AssetReminderState
import com.secman.domain.User
import com.secman.repository.AssetReminderStateRepository
import com.secman.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Covers the outdated-asset fan-out: a single asset can now notify multiple recipients
 * (owner mapping + workgroup members + AWS-account-sharing targets), and the per-asset
 * "already sent today" gate must not let one recipient's send suppress the same asset
 * for another recipient.
 */
class NotificationServiceTest {

    private val emailTemplateService = mockk<EmailTemplateService>(relaxed = true)
    private val reminderStateService = mockk<ReminderStateService>(relaxed = true)
    private val notificationLogService = mockk<NotificationLogService>(relaxed = true)
    private val emailSender = mockk<EmailSender>(relaxed = true)
    private val assetReminderStateRepository = mockk<AssetReminderStateRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)

    private val service = NotificationService(
        emailTemplateService = emailTemplateService,
        reminderStateService = reminderStateService,
        notificationLogService = notificationLogService,
        emailSender = emailSender,
        assetReminderStateRepository = assetReminderStateRepository,
        userRepository = userRepository
    )

    @BeforeEach
    fun setUp() {
        every { reminderStateService.getOrCreateReminderState(any(), any()) } answers {
            AssetReminderState(
                assetId = firstArg<Long>(),
                level = 1,
                lastSentAt = Instant.EPOCH,
                outdatedSince = Instant.now()
            )
        }
        every { reminderStateService.shouldSendToday(any()) } returns true
        every { emailSender.sendEmail(any()) } returns EmailSender.SendResult(success = true, attemptCount = 1)
        // Keep the REPORT-role fan-out out of these assertions.
        every { userRepository.findByRolesContaining(User.Role.REPORT) } returns emptyList()
    }

    private fun outdated(assetId: Long, recipient: String) = NotificationService.OutdatedAssetData(
        assetId = assetId,
        assetName = "asset-$assetId",
        assetType = "EC2",
        ownerEmail = recipient,
        vulnerabilityCount = 1,
        oldestVulnDays = 45,
        oldestVulnId = "CVE-2026-0001",
        severity = "CRITICAL",
        criticality = "HIGH"
    )

    @Test
    fun `shared asset notifies every recipient and is not suppressed for the second recipient`() {
        val sent = mutableListOf<EmailSender.EmailMessage>()
        every { emailSender.sendEmail(capture(sent)) } returns EmailSender.SendResult(success = true, attemptCount = 1)

        // Asset 1 fans out to both recipients; recipient a also owns asset 2.
        val data = listOf(
            outdated(1, "a@example.com"),
            outdated(1, "b@example.com"),
            outdated(2, "a@example.com")
        )

        val result = service.processOutdatedAssets(data, dryRun = false)

        assertThat(result.emailsSent).isEqualTo(2)
        assertThat(result.failures).isEqualTo(0)
        assertThat(sent.map { it.to }).containsExactlyInAnyOrder("a@example.com", "b@example.com")
        // Duplicate-prevention is per-asset-once: updateLastSent fires once per asset, not per recipient.
        verify(exactly = 1) { reminderStateService.updateLastSent(1) }
        verify(exactly = 1) { reminderStateService.updateLastSent(2) }
    }

    @Test
    fun `dry run reports every recipient and never sends or marks sent`() {
        val data = listOf(
            outdated(1, "a@example.com"),
            outdated(1, "b@example.com")
        )

        val result = service.processOutdatedAssets(data, dryRun = true)

        assertThat(result.emailsSent).isEqualTo(2)
        verify(exactly = 0) { emailSender.sendEmail(any()) }
        verify(exactly = 0) { reminderStateService.updateLastSent(any()) }
    }

    @Test
    fun `asset already sent today is skipped for all of its recipients`() {
        every { reminderStateService.shouldSendToday(1) } returns false

        val data = listOf(
            outdated(1, "a@example.com"),
            outdated(1, "b@example.com")
        )

        val result = service.processOutdatedAssets(data, dryRun = false)

        assertThat(result.emailsSent).isEqualTo(0)
        assertThat(result.skipped).containsExactlyInAnyOrder("a@example.com", "b@example.com")
        verify(exactly = 0) { emailSender.sendEmail(any()) }
        verify(exactly = 0) { reminderStateService.updateLastSent(any()) }
    }
}
