package com.secman.service

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
        every { cleanupService.cleanup(7, true, "admin", false, any()) } returns CrowdStrikeAssetCleanupResponse(
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

    // run() selects once with a dry run, sizes the brake from that selection, then hands the
    // same selection to the deleting call. These helpers stub the two calls separately.
    private fun selection(candidates: Int, otherTracked: Long, days: Int = 30) = CrowdStrikeAssetCleanupResponse(
        days = days, cutoff = nowLdt.minusDays(days.toLong()), dryRun = true,
        candidateCount = candidates, deletedCount = 0, skippedCount = 0,
        candidates = (1..candidates).map { i ->
            CrowdStrikeAssetCleanupCandidateDto(i.toLong(), "a$i", nowLdt.minusDays(days + 1L), CleanupCandidateReason.TIMESTAMP_STALE)
        },
        errors = emptyList(),
        // PROTECTED_INVENTORY is not part of the tracked denominator; RECENT_IMPORT is.
        exclusions = mapOf("RECENT_IMPORT" to otherTracked, "PROTECTED_INVENTORY" to 1_000L)
    )

    private fun stubSelection(actor: String, selection: CrowdStrikeAssetCleanupResponse) {
        every { cleanupService.cleanup(selection.days, true, actor, false, any(), any()) } returns selection
    }

    private fun stubDeletion(actor: String, response: CrowdStrikeAssetCleanupResponse) {
        every { cleanupService.cleanup(response.days, false, actor, false, any(), any(), any()) } returns response
    }

    /** Scheduled (braked) runs also require a successful CLI import within the last 24 hours. */
    private fun serviceAfterHealthyImport(): CrowdStrikeCleanupAuditService {
        val lease = CrowdStrikeImportRunLease()
        lease.finish(lease.start("cli"), "cli", successful = true)
        return CrowdStrikeCleanupAuditService(cleanupService, runRepository, assetRepository, notificationService,
            clock, importLease = lease)
    }

    private fun deleted(count: Int, errors: List<CrowdStrikeAssetCleanupErrorDto> = emptyList()) =
        CrowdStrikeAssetCleanupResponse(
            days = 30, cutoff = nowLdt.minusDays(30), dryRun = false,
            candidateCount = count + errors.size, deletedCount = count, skippedCount = errors.size,
            candidates = emptyList(), errors = errors
        )

    @Test
    fun `successful run with deletions persists SUCCESS audit and notifies admins`() {
        stubSelection("scheduler", selection(candidates = 2, otherTracked = 98))
        stubDeletion("scheduler", deleted(2))
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
        stubSelection("admin", selection(candidates = 2, otherTracked = 48))
        stubDeletion("admin", deleted(1, listOf(CrowdStrikeAssetCleanupErrorDto(9, "bad", "lock"))))
        every { runRepository.save(any()) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 7L } }

        val result = service.run(days = 30, dryRun = false, triggeredBy = "admin")

        assertThat(result.status).isEqualTo("PARTIAL")
        verify(exactly = 1) { notificationService.notifyAdmins(any()) }
    }

    @Test
    fun `safety brake aborts when candidate ratio exceeds limit and never calls cleanup service`() {
        // 6 candidates / 50 tracked = 12% > 10% limit
        stubSelection("scheduler", selection(candidates = 6, otherTracked = 44))
        every { runRepository.save(any()) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 99L } }

        val result = serviceAfterHealthyImport().run(
            days = 30, dryRun = false, triggeredBy = "scheduler", maxDeletePercent = 10
        )

        assertThat(result.status).isEqualTo("ABORTED_SAFETY_BRAKE")
        assertThat(result.deletedCount).isEqualTo(0)
        assertThat(result.candidateCount).isEqualTo(6)
        assertThat(result.errors).hasSize(1)
        verify(exactly = 0) { cleanupService.cleanup(any(), false, any(), any(), any(), any(), any()) }
        verify(exactly = 1) { notificationService.notifyAdmins(any()) }
    }

    @Test
    fun `safety brake passes through when ratio is within limit`() {
        // 1 candidate / 100 tracked = 1% <= 10% limit
        stubSelection("scheduler", selection(candidates = 1, otherTracked = 99))
        stubDeletion("scheduler", deleted(1))
        every { runRepository.save(any()) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 1L } }

        val result = serviceAfterHealthyImport().run(
            days = 30, dryRun = false, triggeredBy = "scheduler", maxDeletePercent = 10
        )

        assertThat(result.status).isEqualTo("SUCCESS")
        verify(exactly = 1) { cleanupService.cleanup(30, false, "scheduler", false, any(), any(), any()) }
    }

    @Test
    fun `braked run aborts without a recent successful import and never deletes`() {
        stubSelection("scheduler", selection(candidates = 1, otherTracked = 99))
        every { runRepository.save(any()) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 100L } }

        // setUp's service has a fresh lease: no CLI import has completed since startup.
        val result = service.run(days = 30, dryRun = false, triggeredBy = "scheduler", maxDeletePercent = 10)

        assertThat(result.status).isEqualTo("ABORTED_IMPORT_HEALTH")
        assertThat(result.deletedCount).isEqualTo(0)
        verify(exactly = 0) { cleanupService.cleanup(any(), false, any(), any(), any(), any(), any()) }
        verify(exactly = 1) { notificationService.notifyAdmins(any()) }
    }

    @Test
    fun `selection failure fails closed and never deletes`() {
        every { cleanupService.cleanup(30, true, "scheduler", false, any(), any()) } throws RuntimeException("DB down")
        every { runRepository.save(any()) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 101L } }

        val result = serviceAfterHealthyImport().run(days = 30, dryRun = false, triggeredBy = "scheduler", maxDeletePercent = 10)

        assertThat(result.status).isEqualTo("FAILED")
        assertThat(result.deletedCount).isEqualTo(0)
        verify(exactly = 0) { cleanupService.cleanup(any(), false, any(), any(), any(), any(), any()) }
        verify(exactly = 1) { notificationService.notifyAdmins(any()) }
    }

    @Test
    fun `brake and cleanup share one cutoff instant`() {
        val cutoff = nowLdt.minusDays(30)
        var selectionCutoff: LocalDateTime? = null
        var deletionCutoff: LocalDateTime? = null
        every { cleanupService.cleanup(30, true, "scheduler", false, any(), any()) } answers {
            selectionCutoff = arg(4)
            selection(candidates = 0, otherTracked = 100)
        }
        every { cleanupService.cleanup(30, false, "scheduler", false, any(), any(), any()) } answers {
            deletionCutoff = arg(4)
            deleted(0)
        }
        every { runRepository.save(any()) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 5L } }

        serviceAfterHealthyImport().run(days = 30, dryRun = false, triggeredBy = "scheduler", maxDeletePercent = 10)

        // Selection (which sizes the brake) and deletion must see the same instant, not two clock reads.
        assertThat(selectionCutoff).isEqualTo(cutoff)
        assertThat(deletionCutoff).isEqualTo(cutoff)
    }

    @Test
    fun `run with nothing to delete is SUCCESS and does not notify`() {
        stubSelection("scheduler", selection(candidates = 0, otherTracked = 0))
        stubDeletion("scheduler", deleted(0))
        every { runRepository.save(any()) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 1L } }

        val result = serviceAfterHealthyImport().run(
            days = 30, dryRun = false, triggeredBy = "scheduler", maxDeletePercent = 10
        )

        // No deletions, no errors → SUCCESS, no notification (boring run).
        assertThat(result.status).isEqualTo("SUCCESS")
        verify(exactly = 0) { notificationService.notifyAdmins(any()) }
    }

    @Test
    fun `cleanup service exception is captured as FAILED audit row and notified`() {
        every { assetRepository.findCrowdStrikeStaleExcludingAgentSeen(any(), any()) } returns emptyList()
        every { assetRepository.countCrowdStrikeTracked() } returns 100L
        every { cleanupService.cleanup(any(), any(), any(), any(), any()) } throws RuntimeException("DB down")
        every { runRepository.save(any()) } answers { firstArg<CrowdStrikeCleanupRun>().apply { id = 11L } }

        val result = service.run(
            days = 30, dryRun = false, triggeredBy = "scheduler", maxDeletePercent = 10
        )

        assertThat(result.status).isEqualTo("FAILED")
        assertThat(result.errors).hasSize(1)
        verify(exactly = 1) { notificationService.notifyAdmins(any()) }
    }

    // ---------- Feature 087 — override-vs-default resolution (T012) ----------
    // FR-010: per-run override is optional. null → use configured default;
    // true/false → force the legacy rule for that run regardless of default.

    @Test
    fun `run with includeLegacy=true forces rule B on even when configured default is false`() {
        // Default service in setUp uses includeLegacyDefault = false.
        val cutoff = nowLdt.minusDays(7)
        every { cleanupService.cleanup(7, true, "admin", true, any()) } returns CrowdStrikeAssetCleanupResponse(
            days = 7, cutoff = cutoff, dryRun = true,
            candidateCount = 0, deletedCount = 0, skippedCount = 0,
            candidates = emptyList(), errors = emptyList()
        )

        service.run(days = 7, dryRun = true, triggeredBy = "admin", includeLegacy = true)

        verify(exactly = 1) { cleanupService.cleanup(7, true, "admin", true, any()) }
    }

    @Test
    fun `run with includeLegacy=false forces rule B off even when configured default is true`() {
        val serviceWithDefaultTrue = CrowdStrikeCleanupAuditService(
            cleanupService, runRepository, assetRepository, notificationService, clock,
            includeLegacyDefault = true
        )
        val cutoff = nowLdt.minusDays(7)
        every { cleanupService.cleanup(7, true, "admin", false, any()) } returns CrowdStrikeAssetCleanupResponse(
            days = 7, cutoff = cutoff, dryRun = true,
            candidateCount = 0, deletedCount = 0, skippedCount = 0,
            candidates = emptyList(), errors = emptyList()
        )

        serviceWithDefaultTrue.run(days = 7, dryRun = true, triggeredBy = "admin", includeLegacy = false)

        verify(exactly = 1) { cleanupService.cleanup(7, true, "admin", false, any()) }
    }

    @Test
    fun `run with includeLegacy=null falls back to configured default (false direction)`() {
        // Default service uses includeLegacyDefault = false.
        val cutoff = nowLdt.minusDays(7)
        every { cleanupService.cleanup(7, true, "admin", false, any()) } returns CrowdStrikeAssetCleanupResponse(
            days = 7, cutoff = cutoff, dryRun = true,
            candidateCount = 0, deletedCount = 0, skippedCount = 0,
            candidates = emptyList(), errors = emptyList()
        )

        // No includeLegacy argument → defaults to null → resolves to false.
        service.run(days = 7, dryRun = true, triggeredBy = "admin")

        verify(exactly = 1) { cleanupService.cleanup(7, true, "admin", false, any()) }
    }

    @Test
    fun `run with includeLegacy=null falls back to configured default (true direction)`() {
        val serviceWithDefaultTrue = CrowdStrikeCleanupAuditService(
            cleanupService, runRepository, assetRepository, notificationService, clock,
            includeLegacyDefault = true
        )
        val cutoff = nowLdt.minusDays(7)
        every { cleanupService.cleanup(7, true, "scheduler", true, any()) } returns CrowdStrikeAssetCleanupResponse(
            days = 7, cutoff = cutoff, dryRun = true,
            candidateCount = 0, deletedCount = 0, skippedCount = 0,
            candidates = emptyList(), errors = emptyList()
        )

        // Scheduler call path passes no override → resolves to configured true.
        serviceWithDefaultTrue.run(days = 7, dryRun = true, triggeredBy = "scheduler")

        verify(exactly = 1) { cleanupService.cleanup(7, true, "scheduler", true, any()) }
    }
}
