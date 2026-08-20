package com.secman.scheduler

import com.secman.dto.CrowdStrikeAssetCleanupResponse
import com.secman.service.CrowdStrikeCleanupAuditService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Guards the policy the 02:30 cron applies, now that the manual ADMIN trigger
 * (POST /api/crowdstrike/cleanup/run-now) shares the same method.
 *
 * The load-bearing assertion is that `maxDeletePercent` reaches the audit service
 * NON-null: that is the safety brake which aborts a run that would delete more than
 * the configured share of tracked assets. Passing null here would silently turn the
 * scheduled job into the brake-free variant used by
 * POST /api/assets/delete-not-seen-by-crowdstrike — no test would otherwise notice.
 */
@DisplayName("CrowdStrikeStaleAssetCleanupScheduler policy")
class CrowdStrikeStaleAssetCleanupSchedulerTest {

    private val auditService = mockk<CrowdStrikeCleanupAuditService>(relaxed = true)

    private fun scheduler(
        enabled: Boolean = true,
        staleDays: Int = 30,
        maxDeletePercent: Int = 10
    ) = CrowdStrikeStaleAssetCleanupScheduler(auditService, enabled, staleDays, maxDeletePercent)

    private fun response() = CrowdStrikeAssetCleanupResponse(
        days = 30,
        cutoff = LocalDateTime.of(2026, 1, 1, 0, 0),
        dryRun = false,
        candidateCount = 3,
        deletedCount = 3,
        skippedCount = 0,
        candidates = emptyList(),
        errors = emptyList(),
        status = "SUCCESS",
        runId = 7L
    )

    @Test
    fun `runWithScheduledPolicy applies the configured safety brake`() {
        val brake = slot<Int?>()
        every {
            auditService.run(any(), any(), any(), captureNullable(brake), any())
        } returns response()

        scheduler(maxDeletePercent = 25).runWithScheduledPolicy("scheduler")

        assertEquals(
            25, brake.captured,
            "maxDeletePercent MUST reach the audit service; null would disable the safety brake"
        )
    }

    @Test
    fun `runWithScheduledPolicy uses configured staleDays and never dry-runs`() {
        val days = slot<Int>()
        val dryRun = slot<Boolean>()
        every {
            auditService.run(capture(days), capture(dryRun), any(), any(), any())
        } returns response()

        scheduler(staleDays = 45).runWithScheduledPolicy("scheduler")

        assertEquals(45, days.captured)
        assertEquals(false, dryRun.captured, "The scheduled policy deletes; it is not a preview")
    }

    @Test
    fun `runWithScheduledPolicy leaves includeLegacy at the configured default`() {
        val includeLegacy = slot<Boolean?>()
        every {
            auditService.run(any(), any(), any(), any(), captureNullable(includeLegacy))
        } returns response()

        scheduler().runWithScheduledPolicy("scheduler")

        assertEquals(
            null, includeLegacy.captured,
            "The scheduler must never override include-legacy; null falls back to the configured default"
        )
    }

    @Test
    fun `runWithScheduledPolicy propagates the caller identity to the audit row`() {
        val triggeredBy = slot<String>()
        every {
            auditService.run(any(), any(), capture(triggeredBy), any(), any())
        } returns response()

        scheduler().runWithScheduledPolicy("manual:alice")

        assertEquals("manual:alice", triggeredBy.captured)
    }

    @Test
    fun `runWithScheduledPolicy refuses to run when the feature is disabled`() {
        assertThrows(CleanupDisabledException::class.java) {
            scheduler(enabled = false).runWithScheduledPolicy("manual:alice")
        }
        verify(exactly = 0) { auditService.run(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `runWithScheduledPolicy refuses a non-positive stale-days`() {
        // Loop instead of @ParameterizedTest: junit-jupiter-params is not on the classpath.
        listOf(0, -1).forEach { days ->
            assertThrows(CleanupMisconfiguredException::class.java) {
                scheduler(staleDays = days).runWithScheduledPolicy("manual:alice")
            }
        }
        verify(exactly = 0) { auditService.run(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cron wrapper swallows disabled and misconfigured instead of propagating`() {
        assertDoesNotThrow { scheduler(enabled = false).runScheduledCleanup() }
        assertDoesNotThrow { scheduler(staleDays = 0).runScheduledCleanup() }
    }

    @Test
    fun `cron wrapper swallows an unexpected failure from the audit service`() {
        every { auditService.run(any(), any(), any(), any(), any()) } throws RuntimeException("boom")

        assertDoesNotThrow { scheduler().runScheduledCleanup() }
    }

    @Test
    fun `cron wrapper identifies itself as scheduler`() {
        val triggeredBy = slot<String>()
        every {
            auditService.run(any(), any(), capture(triggeredBy), any(), any())
        } returns response()

        scheduler().runScheduledCleanup()

        assertEquals("scheduler", triggeredBy.captured)
    }
}
