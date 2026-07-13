package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiscoverGithubOwnerMappingsCommandTest {

    private fun result(dryRun: Boolean) = mapOf(
        "status" to if (dryRun) "DRY_RUN" else "SUCCESS",
        "ownersEvaluated" to 2,
        "ownersDiscovered" to 1,
        "discoveredMappings" to listOf(mapOf("owner" to "acme-corp", "email" to "security@acme-corp.example.com", "repoCount" to 3)),
        "ownersSkippedNoPublicEmail" to listOf("some-user"),
        "errors" to emptyList<String>()
    )

    private fun parent() = ManageGithubOwnerMappingsCommand().apply {
        username = "admin"
        password = "secret"
        backendUrl = "https://secman.example.test"
    }

    @Test
    fun `posts dryRun to the discover endpoint`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = DiscoverGithubOwnerMappingsCommand().apply {
            this.parent = parent()
            this.cliHttpClient = cliHttpClient
            this.dryRun = true
        }

        every { cliHttpClient.authenticate("admin", "secret", "https://secman.example.test") } returns "token"
        val bodySlot = slot<Map<String, Any>>()
        every {
            cliHttpClient.postMap(
                "https://secman.example.test/api/github/owner-email-mappings/discover",
                capture(bodySlot),
                "token"
            )
        } returns result(dryRun = true)

        command.run()

        verify(exactly = 1) {
            cliHttpClient.postMap("https://secman.example.test/api/github/owner-email-mappings/discover", any(), "token")
        }
        assertEquals(true, bodySlot.captured["dryRun"])
    }

    @Test
    fun `defaults to a live run when --dry-run is not set`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = DiscoverGithubOwnerMappingsCommand().apply {
            this.parent = parent()
            this.cliHttpClient = cliHttpClient
        }

        every { cliHttpClient.authenticate(any(), any(), any()) } returns "token"
        val bodySlot = slot<Map<String, Any>>()
        every { cliHttpClient.postMap(any(), capture(bodySlot), any()) } returns result(dryRun = false)

        command.run()

        assertEquals(false, bodySlot.captured["dryRun"])
    }
}
