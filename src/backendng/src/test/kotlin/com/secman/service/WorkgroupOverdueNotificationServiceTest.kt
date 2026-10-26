package com.secman.service

import com.secman.config.AppConfig
import com.secman.domain.NotificationType
import com.secman.dto.NotifiedUserStatus
import com.secman.dto.OverdueAssetEntry
import com.secman.dto.OverdueMatchReason
import com.secman.dto.OverdueMatchType
import com.secman.dto.OverdueRecipient
import com.secman.dto.OverdueRecipientOutcome
import com.secman.dto.OverdueWorkgroupPath
import com.secman.repository.NotificationLogRepository
import com.secman.repository.UserRepository
import com.secman.testutil.TestDataFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.concurrent.CompletableFuture

/** Overview statuses, send loop, same-day guard, validation and mail rendering with mocked collaborators. */
class WorkgroupOverdueNotificationServiceTest {
    private val query = mockk<WorkgroupOverdueRecipientQuery>()
    private val users = mockk<UserRepository>()
    private val email = mockk<EmailService>()
    private val logs = mockk<NotificationLogService>(relaxed = true)
    private val logRepository = mockk<NotificationLogRepository>()
    private val service = WorkgroupOverdueNotificationService(
        query, users, email, EmailTemplateRenderer(), logs, logRepository, AppConfig()
    )

    init {
        every { logRepository.findSentRecipientEmailsSince(any(), any()) } returns emptyList()
        every { users.findByEmailIgnoreCase(any()) } returns Optional.empty()
    }

    private fun user(address: String, id: Long, enabled: Boolean = true) =
        TestDataFactory.createRegularUser("user$id", address).apply { this.id = id; this.enabled = enabled }
            .also { every { users.findByEmailIgnoreCase(address) } returns Optional.of(it) }

    private fun asset(id: Long, name: String = "asset-$id") = OverdueAssetEntry(
        assetId = id, assetName = name, ipAddress = null, awsAccountId = "012345678901", overdueCount = 2,
        criticalCount = 1, highCount = 1, mediumCount = 0, lowCount = 0, oldestDays = 45,
        reasons = listOf(OverdueMatchReason(OverdueMatchType.AWS_WORKGROUP, 9, "aws-DevOps-x", "012345678901"))
    )

    private fun path(enabled: Boolean = true, assigned: Long = 0, accounts: List<String> = listOf("012345678901")) =
        OverdueWorkgroupPath(9, "aws-DevOps-x", enabled, assigned, accounts)

    @Test
    fun `overview distinguishes every no-mail outcome`() {
        assertThat(service.overview("ghost@example.com", 30).status).isEqualTo(NotifiedUserStatus.USER_NOT_FOUND)

        user("off@example.com", 1, enabled = false)
        every { query.findWorkgroupPaths(1) } returns listOf(path())
        assertThat(service.overview("off@example.com", 30).status).isEqualTo(NotifiedUserStatus.USER_DISABLED)

        user("lonely@example.com", 2)
        every { query.findWorkgroupPaths(2) } returns listOf(path(enabled = false), path(accounts = emptyList()))
        assertThat(service.overview("lonely@example.com", 30).status).isEqualTo(NotifiedUserStatus.NO_PATHS)

        user("quiet@example.com", 3)
        every { query.findWorkgroupPaths(3) } returns listOf(path())
        every { query.findRecipients(any(), 3) } returns emptyList()
        assertThat(service.overview("quiet@example.com", 30).status).isEqualTo(NotifiedUserStatus.NO_OVERDUE_ASSETS)
    }

    @Test
    fun `overview reports the sender's assets and the same-day guard`() {
        user("member@example.com", 4)
        every { query.findWorkgroupPaths(4) } returns listOf(path())
        every { query.findRecipients(any(), 4) } returns listOf(OverdueRecipient(4, "member@example.com", listOf(asset(1), asset(2))))
        every { logRepository.findSentRecipientEmailsSince(NotificationType.OVERDUE_WORKGROUP_DIGEST, any()) } returns listOf("MEMBER@example.com")

        val overview = service.overview("  Member@Example.com ", 30)

        assertThat(overview.status).isEqualTo(NotifiedUserStatus.WOULD_BE_NOTIFIED)
        assertThat(overview.email).isEqualTo("member@example.com")
        assertThat(overview.totalAssets).isEqualTo(2)
        assertThat(overview.totalOverdue).isEqualTo(4)
        assertThat(overview.alreadyNotifiedToday).isTrue()
    }

    @Test
    fun `dry run sends nothing and already-notified recipients are skipped`() {
        every { query.findRecipients(any(), null) } returns listOf(
            OverdueRecipient(1, "a@example.com", listOf(asset(1))),
            OverdueRecipient(2, "b@example.com", listOf(asset(2)))
        )
        every { logRepository.findSentRecipientEmailsSince(any(), any()) } returns listOf("b@example.com")

        val result = service.run(30, dryRun = true, notificationUser = null, actor = "admin")

        assertThat(result.status).isEqualTo("DRY_RUN")
        assertThat(result.recipients.map { it.outcome })
            .containsExactly(OverdueRecipientOutcome.WOULD_SEND, OverdueRecipientOutcome.SKIPPED_ALREADY_NOTIFIED)
        verify(exactly = 0) { email.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `one mail per recipient, logged, and partial failure is reported`() {
        every { query.findRecipients(any(), null) } returns listOf(
            OverdueRecipient(1, "a@example.com", listOf(asset(1), asset(2))),
            OverdueRecipient(2, "b@example.com", listOf(asset(3)))
        )
        val subject = slot<String>()
        every { email.sendEmailWithInlineImages("a@example.com", capture(subject), any(), any(), any()) } returns
            CompletableFuture.completedFuture(true)
        every { email.sendEmailWithInlineImages("b@example.com", any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(false)

        val result = service.run(30, dryRun = false, notificationUser = null, actor = "admin")

        assertThat(result.status).isEqualTo("PARTIAL_FAILURE")
        assertThat(result.emailsSent).isEqualTo(1)
        assertThat(result.emailsFailed).isEqualTo(1)
        assertThat(subject.captured).isEqualTo(WorkgroupOverdueNotificationService.SUBJECT)
        assertThat(subject.captured).isNotEqualTo("SecMan: Vulnerability Alert for Your AWS Accounts")
        verify(exactly = 1) { email.sendEmailWithInlineImages("a@example.com", any(), any(), any(), any()) }
        verify { logs.logSuccess(null, any(), "a@example.com", NotificationType.OVERDUE_WORKGROUP_DIGEST) }
        verify { logs.logFailure(null, any(), "b@example.com", NotificationType.OVERDUE_WORKGROUP_DIGEST, any()) }
    }

    @Test
    fun `notification user filter is pushed into the query and an unknown user is reported`() {
        val unknown = service.run(30, dryRun = false, notificationUser = "missing@example.com", actor = "admin")
        assertThat(unknown.notificationUserExists).isFalse()
        verify(exactly = 0) { query.findRecipients(any(), any()) }

        user("target@example.com", 8)
        every { query.findRecipients(any(), 8) } returns emptyList()
        val known = service.run(30, dryRun = true, notificationUser = " Target@Example.com", actor = "admin")
        assertThat(known.notificationUserExists).isTrue()
        verify { query.findRecipients(any(), 8) }
    }

    @Test
    fun `invalid days and malformed emails are rejected`() {
        for (days in listOf(0, -1, 3651)) {
            assertThatThrownBy { service.run(days, true, null, "admin") }
                .isInstanceOf(WorkgroupOverdueNotificationService.InvalidRequest::class.java)
        }
        for (address in listOf("", "no-at-sign", "two@@x", "a b@x.com", "x".repeat(250) + "@x.com")) {
            assertThatThrownBy { service.overview(address, 30) }
                .isInstanceOf(WorkgroupOverdueNotificationService.InvalidRequest::class.java)
        }
    }

    @Test
    fun `html mail escapes asset names and lists the reason`() {
        val recipient = OverdueRecipient(1, "a@example.com", listOf(asset(1, "<script>alert(1)</script>")))

        val html = service.renderHtml(recipient, 30)
        val text = service.renderText(recipient, 30)

        assertThat(html).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;")
        assertThat(html).contains("aws-DevOps-x").contains("012345678901").doesNotContain("{assetTableHtml}")
        assertThat(text).contains("<script>alert(1)</script>").contains("via workgroup aws-DevOps-x (AWS account 012345678901)")
    }
}
