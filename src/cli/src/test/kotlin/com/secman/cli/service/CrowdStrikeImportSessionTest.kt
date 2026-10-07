package com.secman.cli.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CrowdStrikeImportSessionTest {
    private val client = mockk<CliHttpClient>()
    private val endpoint = "https://secman.example/api/crowdstrike/servers/import/runs"

    @Test
    fun `long running import renews backend token before expiry`() {
        var now = 0L
        every { client.postMap(endpoint, any(), "initial") } returns mapOf("runId" to "run-1")
        every { client.postMap("$endpoint/run-1/heartbeat", any(), "renewed") } returns mapOf("status" to "ACTIVE")
        every { client.postMap("$endpoint/run-1/finish?successful=true", any(), "renewed") } returns mapOf("status" to "COMPLETED")
        CrowdStrikeImportSession(client, "https://secman.example", "initial", nanoTime = { now }) { "renewed" }.use {
            now = java.util.concurrent.TimeUnit.MINUTES.toNanos(21)
            it.heartbeat()
            org.assertj.core.api.Assertions.assertThat(it.authToken()).isEqualTo("renewed")
            it.complete(true)
        }
        verify { client.postMap("$endpoint/run-1/finish?successful=true", any(), "renewed") }
    }

    @Test
    fun `success is explicit and exceptional exit finalizes failure`() {
        every { client.postMap(endpoint, any(), any()) } returns mapOf("runId" to "run-1")
        every { client.postMap("$endpoint/run-1/heartbeat", any(), any()) } returns mapOf("status" to "ACTIVE")
        every { client.postMap(match { it.contains("/finish?") }, any(), any()) } returns mapOf("status" to "ended")
        CrowdStrikeImportSession(client, "https://secman.example", "token") { "renewed" }.use {
            it.heartbeat()
            it.complete(true)
        }
        verify(exactly = 1) { client.postMap("$endpoint/run-1/finish?successful=true", any(), "token") }
        assertThatThrownBy {
            CrowdStrikeImportSession(client, "https://secman.example", "token") { "renewed" }.use {
                error("fetch failed")
            }
        }.hasMessage("fetch failed")
        verify(exactly = 1) { client.postMap("$endpoint/run-1/finish?successful=false", any(), "token") }
    }

    @Test
    fun `lost heartbeat is a hard failure`() {
        every { client.postMap(endpoint, any(), any()) } returns mapOf("runId" to "run-1")
        every { client.postMap("$endpoint/run-1/heartbeat", any(), any()) } returns null
        every { client.postMap("$endpoint/run-1/finish?successful=false", any(), any()) } returns mapOf("status" to "FAILED")
        CrowdStrikeImportSession(client, "https://secman.example", "token") { "renewed" }.use {
            assertThatThrownBy { it.heartbeat() }.hasMessage("CrowdStrike import heartbeat failed")
        }
    }
}
