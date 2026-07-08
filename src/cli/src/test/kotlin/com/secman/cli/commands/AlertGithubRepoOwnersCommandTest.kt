package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlertGithubRepoOwnersCommandTest {

    private fun dryRunResult() = mapOf(
        "status" to "DRY_RUN",
        "thresholdDays" to 45,
        "reposEvaluated" to 5,
        "reposAlerted" to 2,
        "reposExcepted" to listOf("org/excepted"),
        "reposSkippedInsufficientHistory" to listOf("org/young"),
        "unmappedRepos" to listOf("org/unmapped"),
        "emailsSent" to 0,
        "emailsFailed" to 0,
        "recipients" to emptyList<String>(),
        "failedRecipients" to emptyList<String>()
    )

    @Test
    fun `posts dryRun and thresholdDays to the cli alert endpoint`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = AlertGithubRepoOwnersCommand().apply {
            this.cliHttpClient = cliHttpClient
            this.username = "admin"
            this.password = "secret"
            this.backendUrl = "https://secman.example.test"
            this.dryRun = true
            this.thresholdDays = 45
        }

        every {
            cliHttpClient.authenticate("admin", "secret", "https://secman.example.test")
        } returns "token"
        val bodySlot = slot<Map<String, Any>>()
        every {
            cliHttpClient.postMap(
                "https://secman.example.test/api/cli/github-repo-alerts/send",
                capture(bodySlot),
                "token"
            )
        } returns dryRunResult()

        command.run()

        verify(exactly = 1) {
            cliHttpClient.postMap("https://secman.example.test/api/cli/github-repo-alerts/send", any(), "token")
        }
        assertEquals(true, bodySlot.captured["dryRun"])
        assertEquals(45, bodySlot.captured["thresholdDays"])
    }

    @Test
    fun `defaults to a 30 day window`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = AlertGithubRepoOwnersCommand().apply {
            this.cliHttpClient = cliHttpClient
            this.username = "admin"
            this.password = "secret"
            this.backendUrl = "https://secman.example.test"
            this.dryRun = true
        }

        every { cliHttpClient.authenticate(any(), any(), any()) } returns "token"
        val bodySlot = slot<Map<String, Any>>()
        every { cliHttpClient.postMap(any(), capture(bodySlot), any()) } returns dryRunResult()

        command.run()

        assertEquals(30, bodySlot.captured["thresholdDays"])
        assertEquals(true, bodySlot.captured["dryRun"])
    }
}
