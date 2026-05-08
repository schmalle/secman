package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeCleanupRun
import com.secman.domain.CrowdStrikeCleanupStatus
import com.secman.dto.CleanupCandidateReason
import com.secman.dto.CrowdStrikeAssetCleanupCandidateDto
import com.secman.dto.CrowdStrikeAssetCleanupErrorDto
import com.secman.dto.CrowdStrikeAssetCleanupResponse
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeCleanupRunRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class CrowdStrikeCleanupAuditServiceTest {

    private lateinit var cleanupService: CrowdStrikeAssetCleanupService
    private lateinit var runRepository: CrowdStrikeCleanupRunRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var notificationService: CrowdStrikeCleanupNotificationService
    private lateinit var service: CrowdStrikeCleanupAuditService

    private val now = Instant.parse("2026-05-08T02:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val nowLdt = LocalDateTime.ofInstant(now, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        cleanupService = mockk()
        runRepository = mockk()
        assetRepository = mockk()
        notificationService = mockk(relaxed = true)
        service = CrowdStrikeCleanupAuditService(
            cleanupService, runRepository, assetRepository, notificationService, clock
        )
    }

    @Test
    fun `dry run delegates to cleanup service and never persists or notifies`() {
        every { cleanupService.cleanup(7, true, "admin") } returns CrowdStrikeAssetCleanupResponse(
            days = 7,
            cutoff = nowLdt.minusDays(7),
            dryRun = true,
            candidateCount = 3,
            deletedCount = 0,
            skippedCount = 3,
            candidates = emptyList(),
            errors = emptyList()
        )

        val result = service.run(days = 7, dryRun = true, triggeredBy = "admin")

        assertThat(result.candidateCount).isEqualTo(3)
        verify(exactly = 0) { runRepository.save(any()) }
        verify(exactly = 0) { notificationService.notifyAdmins(any()) }
    }

    @Test
    fun `successful run with deletions persists SUCCESS audit and notifies admins`() {
        val cutoff = nowLdt.minusDays(30)
        every { cleanupService.cleanup(30, false, "scheduler") } returns CrowdStrikeAssetCleanupResponse(
            days = 30,
            cutoff = cutoff,
            dryRun = false,
            candidateCount = 2,
            deletedCount = 2,
            skippedCount = 0,
            candidates = listOf(CrowdStrikeAssetCleanupCandidateDto(1, "a", cutoff.minusDays(1), CleanupCandidateReason.TIMESTAMP_STALE)),
            errors = emptyList()
        )
        every { assetRepository.countCrowdStrikeTracked() } returns 100L
        val saved = slot<CrowdStrikeCleanupRun>()
        every { runRepository.save(capture(saved)) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 42L } }

        val result = service.run(days = 30, dryRun = false, triggeredBy = "scheduler")

        assertThat(result.status).isEqualTo("SUCCESS")
        assertThat(result.runId).isEqualTo(42L)
        assertThat(saved.captured.status).isEqualTo(CrowdStrikeCleanupStatus.SUCCESS)
        assertThat(saved.captured.deletedCount).isEqualTo(2)
        assertThat(saved.captured.totalCrowdStrikeTracked).isEqualTo(100L)
        verify(exactly = 1) { notificationService.notifyAdmins(any()) }
    }

    @Test
    fun `run with errors records PARTIAL status and notifies`() {
        val cutoff = nowLdt.minusDays(30)
        every { cleanupService.cleanup(30, false, "admin") } returns CrowdStrikeAssetCleanupResponse(
            days = 30,
            cutoff = cutoff,
            dryRun = false,
            candidateCount = 2,
            deletedCount = 1,
            skippedCount = 1,
            candidates = emptyList(),
            errors = listOf(CrowdStrikeAssetCleanupErrorDto(9, "bad", "lock"))
        )
        every { assetRepository.countCrowdStrikeTracked() } returns 50L
        every { runRepository.save(any()) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 7L } }

        val result = service.run(days = 30, dryRun = false, triggeredBy = "admin")

        assertThat(result.status).isEqualTo("PARTIAL")
        verify(exactly = 1) { notificationService.notifyAdmins(any()) }
    }

    @Test
    fun `safety brake aborts when candidate ratio exceeds limit and never calls cleanup service`() {
        val cutoff = nowLdt.minusDays(30)
        // 6 candidates / 50 tracked = 12% > 10% limit
        every { assetRepository.findByCrowdStrikeLastImportedAtBefore(cutoff) } returns
            (1..6).map { i ->
                Asset(
                    id = i.toLong(),
                    name = "a$i",
                    type = "SERVER",
                    owner = "x",
                    crowdStrikeLastImportedAt = cutoff.minusDays(1)
                )
            }
        every { assetRepository.countCrowdStrikeTracked() } returns 50L
        every { runRepository.save(any()) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 99L } }

        val result = service.run(
            days = 30, dryRun = false, triggeredBy = "scheduler", maxDeletePercent = 10
        )

        assertThat(result.status).isEqualTo("ABORTED_SAFETY_BRAKE")
        assertThat(result.deletedCount).isEqualTo(0)
        assertThat(result.candidateCount).isEqualTo(6)
        assertThat(result.errors).hasSize(1)
        verify(exactly = 0) { cleanupService.cleanup(any(), any(), any()) }
        verify(exactly = 1) { notificationService.notifyAdmins(any()) }
    }

    @Test
    fun `safety brake passes through when ratio is within limit`() {
        val cutoff = nowLdt.minusDays(30)
        every { assetRepository.findByCrowdStrikeLastImportedAtBefore(cutoff) } returns
            listOf(
                Asset(
                    id = 1L,
                    name = "a",
                    type = "SERVER",
                    owner = "x",
                    crowdStrikeLastImportedAt = cutoff.minusDays(1)
                )
            )
        every { assetRepository.countCrowdStrikeTracked() } returns 100L
        every { cleanupService.cleanup(30, false, "scheduler") } returns CrowdStrikeAssetCleanupResponse(
            days = 30,
            cutoff = cutoff,
            dryRun = false,
            candidateCount = 1,
            deletedCount = 1,
            skippedCount = 0,
            candidates = emptyList(),
            errors = emptyList()
        )
        every { runRepository.save(any()) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 1L } }

        val result = service.run(
            days = 30, dryRun = false, triggeredBy = "scheduler", maxDeletePercent = 10
        )

        assertThat(result.status).isEqualTo("SUCCESS")
        verify(exactly = 1) { cleanupService.cleanup(30, false, "scheduler") }
    }

    @Test
    fun `safety brake skipped when total tracked is zero`() {
        every { assetRepository.findByCrowdStrikeLastImportedAtBefore(any()) } returns emptyList()
        every { assetRepository.countCrowdStrikeTracked() } returns 0L
        every { cleanupService.cleanup(any(), any(), any()) } returns CrowdStrikeAssetCleanupResponse(
            days = 30,
            cutoff = nowLdt.minusDays(30),
            dryRun = false,
            candidateCount = 0,
            deletedCount = 0,
            skippedCount = 0,
            candidates = emptyList(),
            errors = emptyList()
        )
        every { runRepository.save(any()) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 1L } }

        val result = service.run(
            days = 30, dryRun = false, triggeredBy = "scheduler", maxDeletePercent = 10
        )

        // No deletions, no errors → SUCCESS, no notification (boring run).
        assertThat(result.status).isEqualTo("SUCCESS")
        verify(exactly = 0) { notificationService.notifyAdmins(any()) }
    }

    @Test
    fun `cleanup service exception is captured as FAILED audit row and notified`() {
        every { assetRepository.findByCrowdStrikeLastImportedAtBefore(any()) } returns emptyList()
        every { assetRepository.countCrowdStrikeTracked() } returns 100L
        every { cleanupService.cleanup(any(), any(), any()) } throws RuntimeException("DB down")
        every { runRepository.save(any()) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 11L } }

        val result = service.run(
            days = 30, dryRun = false, triggeredBy = "scheduler", maxDeletePercent = 10
        )

        assertThat(result.status).isEqualTo("FAILED")
        assertThat(result.errors).hasSize(1)
        verify(exactly = 1) { notificationService.notifyAdmins(any()) }
    }
}
