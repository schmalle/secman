package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command to add a GitHub owner email mapping.
 *
 * Usage:
 *   ./scripts/secman manage-github-owner-mappings add --owner acme-corp --email owner@example.com
 */
@Singleton
@Command(
    name = "add",
    description = ["Add a GitHub owner email mapping"],
    mixinStandardHelpOptions = true
)
class AddGithubOwnerMappingCommand : Runnable {

    @Option(names = ["--owner"], description = ["GitHub owner login (org or user)"], required = true)
    lateinit var owner: String

    @Option(names = ["--email"], description = ["Default notification email for this owner's repositories"], required = true)
    lateinit var email: String

    @ParentCommand
    lateinit var parent: ManageGithubOwnerMappingsCommand

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    override fun run() {
        try {
            val backendUrl = parent.getEffectiveBackendUrl()
            val authToken = cliHttpClient.authenticate(parent.getEffectiveUsername(), parent.getEffectivePassword(), backendUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val (status, result) = cliHttpClient.postMapWithStatus(
                "$backendUrl/api/github/owner-email-mappings",
                mapOf("owner" to owner.trim(), "email" to email.trim()),
                authToken
            )

            if (status !in 200..299 || result == null) {
                val error = result?.get("error")?.toString() ?: "Backend returned HTTP $status"
                throw RuntimeException(error)
            }

            println("Mapping created: ${result["owner"]} -> ${result["email"]} (${result["repoCount"]} repo(s) backfilled if blank)")
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            System.exit(1)
        }
    }
}
