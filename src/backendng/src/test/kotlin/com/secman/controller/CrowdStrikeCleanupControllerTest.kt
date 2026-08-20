package com.secman.controller

import com.secman.dto.CrowdStrikeAssetCleanupResponse
import com.secman.repository.CrowdStrikeCleanupRunRepository
import com.secman.scheduler.CleanupDisabledException
import com.secman.scheduler.CleanupMisconfiguredException
import com.secman.scheduler.CrowdStrikeStaleAssetCleanupScheduler
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Unit tests for [CrowdStrikeCleanupController]. Backs spec FR-009 + SC-006:
 * the cleanup-config endpoint MUST expose `includeLegacy` so the admin UI
 * initialises the toggle from the configured backend default rather than
 * a hardcoded value.
 */
class CrowdStrikeCleanupControllerTest {

    private val runRepository: CrowdStrikeCleanupRunRepository = mockk()
    private val scheduler: CrowdStrikeStaleAssetCleanupScheduler = mockk()

    @Test
    fun `getConfig exposes includeLegacy=false when configured default is false`() {
        val controller = CrowdStrikeCleanupController(
            runRepository = runRepository,
            scheduler = scheduler,
            enabled = false,
            staleDays = 30,
            maxDeletePercent = 10,
            includeLegacy = false
        )

        val response = controller.getConfig()

        assertEquals(200, response.status.code)
        val body = response.body()
        assertNotNull(body)
        assertEquals(false, body!!.includeLegacy, "Configured default false MUST round-trip through the response")
        // Existing fields unchanged
        assertEquals(false, body.enabled)
        assertEquals(30, body.staleDays)
        assertEquals(10, body.maxDeletePercent)
        assertEquals("0 30 2 * * ?", body.cron)
    }

    @Test
    fun `getConfig exposes includeLegacy=true when configured default is true`() {
        val controller = CrowdStrikeCleanupController(
            runRepository = runRepository,
            scheduler = scheduler,
            enabled = true,
            staleDays = 30,
            maxDeletePercent = 10,
            includeLegacy = true
        )

        val body = controller.getConfig().body()
        assertNotNull(body)
        assertEquals(true, body!!.includeLegacy, "Configured default true MUST round-trip through the response")
    }

    // --- POST /run-now: replay the 02:30 scheduled policy ------------------------

    private fun controller(
        enabled: Boolean = true,
        staleDays: Int = 30,
        maxDeletePercent: Int = 10
    ) = CrowdStrikeCleanupController(
        runRepository = runRepository,
        scheduler = scheduler,
        enabled = enabled,
        staleDays = staleDays,
        maxDeletePercent = maxDeletePercent,
        includeLegacy = false
    )

    private fun auth(name: String = "admin"): Authentication =
        mockk<Authentication>().also { every { it.name } returns name }

    @Test
    fun `runNow delegates to the scheduler policy and tags the run with the admin name`() {
        val triggeredBy = slot<String>()
        every { scheduler.runWithScheduledPolicy(capture(triggeredBy)) } returns
            CrowdStrikeAssetCleanupResponse(
                days = 30,
                cutoff = LocalDateTime.of(2026, 1, 1, 0, 0),
                dryRun = false,
                candidateCount = 2,
                deletedCount = 2,
                skippedCount = 0,
                candidates = emptyList(),
                errors = emptyList(),
                status = "SUCCESS",
                runId = 11L
            )

        val response = controller().runNow(auth("alice"))

        assertEquals(200, response.status.code)
        assertEquals(
            "manual:alice", triggeredBy.captured,
            "The audit row must distinguish a manual replay from the 02:30 scheduler run"
        )
    }

    @Test
    fun `runNow returns 409 when the cleanup feature is disabled`() {
        every { scheduler.runWithScheduledPolicy(any()) } throws
            CleanupDisabledException("CrowdStrike cleanup is disabled (secman.crowdstrike.cleanup.enabled=false)")

        val response = controller(enabled = false).runNow(auth())

        assertEquals(409, response.status.code)
    }

    @Test
    fun `runNow returns 400 when stale-days is misconfigured`() {
        every { scheduler.runWithScheduledPolicy(any()) } throws
            CleanupMisconfiguredException("CrowdStrike cleanup misconfigured: stale-days=0 (must be > 0)")

        val response = controller(staleDays = 0).runNow(auth())

        assertEquals(400, response.status.code)
    }

    @Test
    fun `runNow returns a generic 500 without leaking the internal failure`() {
        every { scheduler.runWithScheduledPolicy(any()) } throws
            RuntimeException("could not extract ResultSet; SQL [select a1_0.id from asset a1_0]")

        val response = controller().runNow(auth())

        assertEquals(500, response.status.code)
        val body = response.body()
        assertNotNull(body)
        val error = (body as CrowdStrikeCleanupController.ErrorResponse).error
        assertTrue(
            !error.contains("SQL", ignoreCase = true) && !error.contains("ResultSet"),
            "Internal driver/SQL detail must stay in the log, never in the response (OWASP A05). Got: $error"
        )
    }

    @Test
    fun `runNow never applies its own threshold - the policy is the scheduler's`() {
        every { scheduler.runWithScheduledPolicy(any()) } returns
            CrowdStrikeAssetCleanupResponse(
                days = 30,
                cutoff = LocalDateTime.of(2026, 1, 1, 0, 0),
                dryRun = false,
                candidateCount = 0,
                deletedCount = 0,
                skippedCount = 0,
                candidates = emptyList(),
                errors = emptyList(),
                status = "SUCCESS",
                runId = 12L
            )

        controller(staleDays = 45, maxDeletePercent = 25).runNow(auth())

        // The controller must not reach past the scheduler into the audit service with
        // its own copy of the config -- that is exactly the drift this design prevents.
        verify(exactly = 1) { scheduler.runWithScheduledPolicy(any()) }
    }
}
