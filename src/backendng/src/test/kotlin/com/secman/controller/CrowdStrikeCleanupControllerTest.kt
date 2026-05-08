package com.secman.controller

import com.secman.repository.CrowdStrikeCleanupRunRepository
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CrowdStrikeCleanupController]. Backs spec FR-009 + SC-006:
 * the cleanup-config endpoint MUST expose `includeLegacy` so the admin UI
 * initialises the toggle from the configured backend default rather than
 * a hardcoded value.
 */
class CrowdStrikeCleanupControllerTest {

    private val runRepository: CrowdStrikeCleanupRunRepository = mockk()

    @Test
    fun `getConfig exposes includeLegacy=false when configured default is false`() {
        val controller = CrowdStrikeCleanupController(
            runRepository = runRepository,
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
            enabled = true,
            staleDays = 30,
            maxDeletePercent = 10,
            includeLegacy = true
        )

        val body = controller.getConfig().body()
        assertNotNull(body)
        assertEquals(true, body!!.includeLegacy, "Configured default true MUST round-trip through the response")
    }
}
