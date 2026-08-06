package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command to list GitHub owner email mappings.
 *
 * Usage:
 *   ./scripts/secman manage-github-owner-mappings list
 */
@Singleton
@Command(
    name = "list",
    description = ["List GitHub owner email mappings"],
    mixinStandardHelpOptions = true
)
class ListGithubOwnerMappingsCommand : Runnable {

    @ParentCommand
    lateinit var parent: ManageGithubOwnerMappingsCommand

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    override fun run() {
        try {
            val backendUrl = parent.getEffectiveBackendUrl()
            val authToken = cliHttpClient.authenticate(parent.getEffectiveUsername(), parent.getEffectivePassword(), backendUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val mappings = cliHttpClient.getList("$backendUrl/api/github/owner-email-mappings", authToken)
                ?: throw RuntimeException("Failed to fetch mappings")

            if (mappings.isEmpty()) {
                println("No GitHub owner email mappings found")
                return
            }

            println("%-4s %-30s %-30s %-6s %s".format("ID", "Owner", "Email", "Repos", "Created by"))
            mappings.forEach { m ->
                println(
                    "%-4s %-30s %-30s %-6s %s".format(
                        m["id"]?.toString() ?: "",
                        m["owner"]?.toString() ?: "",
                        m["email"]?.toString() ?: "",
                        m["repoCount"]?.toString() ?: "0",
                        m["createdBy"]?.toString() ?: ""
                    )
                )
            }
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            System.exit(1)
        }
    }
}
