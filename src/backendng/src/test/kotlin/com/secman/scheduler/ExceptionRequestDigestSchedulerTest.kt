package com.secman.scheduler

import com.secman.config.ExceptionNotificationConfig
import com.secman.domain.ExceptionRequestStatus
import com.secman.domain.VulnerabilityException
import com.secman.domain.VulnerabilityExceptionRequest
import com.secman.repository.VulnerabilityExceptionRequestRepository
import com.secman.service.ExceptionRequestNotificationService
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

class ExceptionRequestDigestSchedulerTest {

    private lateinit var repository: VulnerabilityExceptionRequestRepository
    private lateinit var notificationService: ExceptionRequestNotificationService
    private lateinit var scheduler: ExceptionRequestDigestScheduler

    @BeforeEach
    fun setUp() {
        repository = mockk(relaxed = true)
        notificationService = mockk()
    }

    private fun newScheduler(mode: String) {
        scheduler = ExceptionRequestDigestScheduler(
            repository, notificationService, ExceptionNotificationConfig(mode = mode)
        )
    }

    private fun request(cve: String): VulnerabilityExceptionRequest =
        VulnerabilityExceptionRequest(
            requestedByUsername = "alice",
            subject = VulnerabilityException.Subject.CVE,
            scope = VulnerabilityException.Scope.ASSET,
            reason = "A sufficiently long business justification that satisfies the minimum length rule.",
            expirationDate = LocalDateTime.now().plusDays(30),
            status = ExceptionRequestStatus.PENDING,
            cveId = cve,
            assetId = 1L
        ).apply { createdAt = LocalDateTime.of(2026, 6, 18, 9, 0) }

    @Test
    fun `stamps adminNotifiedAt and dispatches digest when pending requests exist`() {
        newScheduler("digest")
        val pending = listOf(request("CVE-1"), request("CVE-2"))
        every {
            repository.findByStatusAndAdminNotifiedAtIsNullOrderByCreatedAt(ExceptionRequestStatus.PENDING)
        } returns pending
        every { notificationService.notifyAdminsOfPendingDigest(pending) } returns
            CompletableFuture.completedFuture(true)

        scheduler.sendPendingDigest()

        verify(exactly = 1) { notificationService.notifyAdminsOfPendingDigest(pending) }
        pending.forEach { assertNotNull(it.adminNotifiedAt) }
        verify(exactly = 2) { repository.update(any<VulnerabilityExceptionRequest>()) }
    }

    @Test
    fun `does nothing when there are no un-notified pending requests`() {
        newScheduler("digest")
        every {
            repository.findByStatusAndAdminNotifiedAtIsNullOrderByCreatedAt(ExceptionRequestStatus.PENDING)
        } returns emptyList()

        scheduler.sendPendingDigest()

        verify(exactly = 0) { notificationService.notifyAdminsOfPendingDigest(any()) }
        verify(exactly = 0) { repository.update(any<VulnerabilityExceptionRequest>()) }
    }

    @Test
    fun `leaves requests un-notified for retry when digest send fails`() {
        newScheduler("digest")
        val pending = listOf(request("CVE-1"))
        every {
            repository.findByStatusAndAdminNotifiedAtIsNullOrderByCreatedAt(ExceptionRequestStatus.PENDING)
        } returns pending
        every { notificationService.notifyAdminsOfPendingDigest(pending) } returns
            CompletableFuture.completedFuture(false)

        scheduler.sendPendingDigest()

        assertNull(pending[0].adminNotifiedAt)
        verify(exactly = 0) { repository.update(any<VulnerabilityExceptionRequest>()) }
    }

    @Test
    fun `skips entirely in immediate mode`() {
        newScheduler("immediate")

        scheduler.sendPendingDigest()

        verify(exactly = 0) {
            repository.findByStatusAndAdminNotifiedAtIsNullOrderByCreatedAt(any())
        }
        verify(exactly = 0) { notificationService.notifyAdminsOfPendingDigest(any()) }
    }
}
