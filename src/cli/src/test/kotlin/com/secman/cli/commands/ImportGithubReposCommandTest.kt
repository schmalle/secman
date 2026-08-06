package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ImportGithubReposCommandTest {

    @Test
    fun `posts to the github import endpoint and succeeds on a clean result`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = ImportGithubReposCommand().apply {
            this.cliHttpClient = cliHttpClient
            this.username = "admin"
            this.password = "secret"
            this.backendUrl = "https://secman.example.test"
        }

        every {
            cliHttpClient.authenticate("admin", "secret", "https://secman.example.test")
        } returns "token"
        every {
            cliHttpClient.postMapWithStatus("https://secman.example.test/api/github/import", any(), "token")
        } returns (200 to mapOf(
            "reposDiscovered" to 3,
            "reposNew" to 1,
            "reposUpdated" to 2,
            "totalCritical" to 4,
            "totalHigh" to 7,
            "reposWithAlertsDisabled" to listOf("org/legacy"),
            "errors" to emptyList<String>(),
            "importedAt" to "2026-07-08T00:00:00Z"
        ))

        command.run()

        verify(exactly = 1) {
            cliHttpClient.postMapWithStatus("https://secman.example.test/api/github/import", any(), "token")
        }
    }

    @Test
    fun `backend url without scheme is prefixed with https`() {
        val cliHttpClient = mockk<CliHttpClient>()
        val command = ImportGithubReposCommand().apply {
            this.cliHttpClient = cliHttpClient
            this.username = "admin"
            this.password = "secret"
            this.backendUrl = "secman.example.test"
        }

        every { cliHttpClient.authenticate(any(), any(), any()) } returns "token"
        every { cliHttpClient.postMapWithStatus(any(), any(), any()) } returns (200 to mapOf(
            "reposDiscovered" to 0, "reposNew" to 0, "reposUpdated" to 0,
            "totalCritical" to 0, "totalHigh" to 0,
            "reposWithAlertsDisabled" to emptyList<String>(),
            "errors" to emptyList<String>()
        ))

        command.run()

        verify {
            cliHttpClient.postMapWithStatus("https://secman.example.test/api/github/import", any(), "token")
        }
    }
}
