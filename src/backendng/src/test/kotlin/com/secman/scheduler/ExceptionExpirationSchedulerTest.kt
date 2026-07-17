package com.secman.scheduler

import com.secman.domain.ExceptionRequestStatus
import com.secman.domain.VulnerabilityException
import com.secman.domain.VulnerabilityExceptionRequest
import com.secman.repository.VulnerabilityExceptionRepository
import com.secman.repository.VulnerabilityExceptionRequestRepository
import com.secman.service.ActiveExceptionsCacheInvalidator
import com.secman.service.ExceptionRequestAuditService
import com.secman.service.ExceptionRequestNotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

/**
 * Concurrency-safety tests for the expiration scheduler: overlapping runs (a second app
 * instance, or a run overlapping a slow previous one) must process each request's side
 * effects exactly once - enforced by atomic per-request claims.
 */
@DisplayName("ExceptionExpirationScheduler claims")
class ExceptionExpirationSchedulerTest {

    private val requestRepository = mockk<VulnerabilityExceptionRequestRepository>(relaxed = true)
    private val exceptionRepository = mockk<VulnerabilityExceptionRepository>(relaxed = true)
    private val auditService = mockk<ExceptionRequestAuditService>(relaxed = true)
    private val notificationService = mockk<ExceptionRequestNotificationService>(relaxed = true)
    private val cacheInvalidator = mockk<ActiveExceptionsCacheInvalidator>(relaxed = true)

    private lateinit var scheduler: ExceptionExpirationScheduler

    @BeforeEach
    fun setUp() {
        scheduler = ExceptionExpirationScheduler(
            requestRepository, exceptionRepository, auditService, notificationService, cacheInvalidator
        )
        // Production gets the AOP self-proxy injected; the plain instance suffices here.
        scheduler.selfProvider = jakarta.inject.Provider { scheduler }
        every { notificationService.notifyRequesterOfExpiration(any()) } returns
            CompletableFuture.completedFuture(true)
        every { notificationService.notifyAdminsAndSecChampionsOfExpiration(any()) } returns
            CompletableFuture.completedFuture(true)
    }

    private fun approvedRequest(id: Long) = VulnerabilityExceptionRequest(
        id = id,
        requestedByUsername = "alice",
        subject = VulnerabilityException.Subject.CVE,
        scope = VulnerabilityException.Scope.GLOBAL,
        subjectValue = "CVE-2026-0001",
        scopeValue = null,
        reason = "expired exception request under test - reason padding",
        expirationDate = LocalDateTime.now().minusDays(1),
        status = ExceptionRequestStatus.APPROVED,
        autoApproved = false,
        cveId = "CVE-2026-0001",
        assetId = null
    ).apply {
        createdAt = LocalDateTime.now().minusDays(30)
        updatedAt = LocalDateTime.now().minusDays(1)
        version = 0
    }

    @Test
    fun `expiration winner runs side effects exactly once`() {
        val request = approvedRequest(1L)
        every {
            requestRepository.findByStatusAndExpirationDateLessThanEqual(ExceptionRequestStatus.APPROVED, any())
        } returns listOf(request)
        every {
            requestRepository.claimStatusTransition(1L, ExceptionRequestStatus.APPROVED, ExceptionRequestStatus.EXPIRED, any())
        } returns 1

        scheduler.processExpirations()

        verify(exactly = 1) { notificationService.notifyRequesterOfExpiration(request) }
        verify(exactly = 1) { notificationService.notifyAdminsAndSecChampionsOfExpiration(request) }
        verify(exactly = 1) { auditService.logExpiration(request) }
    }

    @Test
    fun `lost expiration claim skips all side effects`() {
        val request = approvedRequest(2L)
        every {
            requestRepository.findByStatusAndExpirationDateLessThanEqual(ExceptionRequestStatus.APPROVED, any())
        } returns listOf(request)
        // A concurrent run already flipped APPROVED -> EXPIRED: 0 rows affected here.
        every {
            requestRepository.claimStatusTransition(2L, ExceptionRequestStatus.APPROVED, ExceptionRequestStatus.EXPIRED, any())
        } returns 0

        scheduler.processExpirations()

        verify(exactly = 0) { notificationService.notifyRequesterOfExpiration(any()) }
        verify(exactly = 0) { notificationService.notifyAdminsAndSecChampionsOfExpiration(any()) }
        verify(exactly = 0) { auditService.logExpiration(any()) }
        verify(exactly = 0) { exceptionRepository.delete(any()) }
    }

    @Test
    fun `reminder is sent only by the claim winner`() {
        val request = approvedRequest(3L).apply {
            expirationDate = LocalDateTime.now().plusDays(3)
            reminderSentAt = null
        }
        every {
            requestRepository.findByStatusAndExpirationDateBetween(ExceptionRequestStatus.APPROVED, any(), any())
        } returns listOf(request)
        every { requestRepository.claimReminder(3L, any()) } returns 1

        scheduler.sendExpirationReminders()

        verify(exactly = 1) { notificationService.notifyRequesterOfExpiration(request) }
        verify(exactly = 0) { requestRepository.releaseReminderClaim(any(), any()) }
    }

    @Test
    fun `lost reminder claim sends nothing`() {
        val request = approvedRequest(4L).apply {
            expirationDate = LocalDateTime.now().plusDays(3)
            reminderSentAt = null
        }
        every {
            requestRepository.findByStatusAndExpirationDateBetween(ExceptionRequestStatus.APPROVED, any(), any())
        } returns listOf(request)
        every { requestRepository.claimReminder(4L, any()) } returns 0

        scheduler.sendExpirationReminders()

        verify(exactly = 0) { notificationService.notifyRequesterOfExpiration(any()) }
    }

    @Test
    fun `failed reminder send releases the claim for retry`() {
        val request = approvedRequest(5L).apply {
            expirationDate = LocalDateTime.now().plusDays(3)
            reminderSentAt = null
        }
        every {
            requestRepository.findByStatusAndExpirationDateBetween(ExceptionRequestStatus.APPROVED, any(), any())
        } returns listOf(request)
        every { requestRepository.claimReminder(5L, any()) } returns 1
        every { notificationService.notifyRequesterOfExpiration(request) } returns
            CompletableFuture.completedFuture(false)

        scheduler.sendExpirationReminders()

        verify(exactly = 1) { requestRepository.releaseReminderClaim(5L, any()) }
    }
}
