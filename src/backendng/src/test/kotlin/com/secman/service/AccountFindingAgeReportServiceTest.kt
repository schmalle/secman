package com.secman.service

import com.secman.config.AppConfig
import com.secman.domain.ExecutionStatus
import com.secman.domain.User
import com.secman.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

class AccountFindingAgeReportServiceTest {

    private val accountFindingAgeService = mockk<AccountFindingAgeService>()
    private val userRepository = mockk<UserRepository>()
    private val emailService = mockk<EmailService>(relaxed = true)
    private val appConfig = mockk<AppConfig>(relaxed = true)

    private val service = AccountFindingAgeReportService(
        accountFindingAgeService, userRepository, emailService, appConfig
    )

    private fun user(name: String, email: String, role: User.Role) =
        User(username = name, email = email, passwordHash = "x", roles = mutableSetOf(role))

    private fun row() = AccountFindingAgeService.AccountFindingAge(
        awsAccountId = "111111111111",
        accountName = "Platform Prod",
        oldestFindingFirstSeenAt = LocalDateTime.now().minusDays(300),
        oldestFindingDaysOpen = 300,
        oldestFindingCve = "CVE-2023-1",
        oldestFindingSeverity = "Critical",
        oldestFindingAssetName = "web-01",
        oldestFindingAssetInstanceId = "i-0abc",
        openFindingCount = 12,
        affectedAssetCount = 3
    )

    @Test
    fun `recipients are ADMIN users only and never REPORT users`(): Unit {
        every { accountFindingAgeService.getTopAccountsByOldestFinding(any()) } returns listOf(row())
        every { userRepository.findByRolesContaining(User.Role.ADMIN) } returns
            listOf(user("admin", "admin@example.com", User.Role.ADMIN))
        every { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(true)

        val result = service.sendReport(limit = 10, dryRun = false, verbose = false)

        assertThat(result.recipients).containsExactly("admin@example.com")
        verify(exactly = 0) { userRepository.findByRolesContaining(User.Role.REPORT) }
    }

    @Test
    fun `users without an email address are skipped`(): Unit {
        every { accountFindingAgeService.getTopAccountsByOldestFinding(any()) } returns listOf(row())
        every { userRepository.findByRolesContaining(User.Role.ADMIN) } returns listOf(
            user("admin", "admin@example.com", User.Role.ADMIN),
            user("noemail", "", User.Role.ADMIN)
        )
        every { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(true)

        val result = service.sendReport(limit = 10, dryRun = false, verbose = false)

        assertThat(result.recipientCount).isEqualTo(1)
    }

    @Test
    fun `dry run sends nothing but reports the planned recipients`(): Unit {
        every { accountFindingAgeService.getTopAccountsByOldestFinding(any()) } returns listOf(row())
        every { userRepository.findByRolesContaining(User.Role.ADMIN) } returns
            listOf(user("admin", "admin@example.com", User.Role.ADMIN))

        val result = service.sendReport(limit = 10, dryRun = true, verbose = false)

        assertThat(result.status).isEqualTo(ExecutionStatus.DRY_RUN)
        assertThat(result.emailsSent).isZero()
        assertThat(result.recipients).containsExactly("admin@example.com")
        verify(exactly = 0) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an empty report sends nothing and succeeds`(): Unit {
        every { accountFindingAgeService.getTopAccountsByOldestFinding(any()) } returns emptyList()
        every { userRepository.findByRolesContaining(User.Role.ADMIN) } returns
            listOf(user("admin", "admin@example.com", User.Role.ADMIN))

        val result = service.sendReport(limit = 10, dryRun = false, verbose = false)

        assertThat(result.status).isEqualTo(ExecutionStatus.SUCCESS)
        assertThat(result.accountCount).isZero()
        assertThat(result.emailsSent).isZero()
        assertThat(result.recipientCount).isZero()
        assertThat(result.recipients).isEmpty()
        verify(exactly = 0) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }
}
