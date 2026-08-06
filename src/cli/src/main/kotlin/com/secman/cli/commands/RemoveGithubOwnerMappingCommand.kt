package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command to remove a GitHub owner email mapping.
 *
 * Usage:
 *   ./scripts/secman manage-github-owner-mappings remove --owner acme-corp
 *
 * Does not un-set any ownerEmail the mapping previously backfilled on repos.
 */
@Singleton
@Command(
    name = "remove",
    description = ["Remove a GitHub owner email mapping"],
    mixinStandardHelpOptions = true
)
class RemoveGithubOwnerMappingCommand : Runnable {

    @Option(names = ["--owner"], description = ["GitHub owner login to remove the mapping for"], required = true)
    lateinit var owner: String

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

            val match = mappings.firstOrNull { it["owner"]?.toString()?.equals(owner, ignoreCase = true) == true }
                ?: throw RuntimeException("No mapping found for owner '$owner'")
            val id = (match["id"] as? Number)?.toLong()
                ?: throw RuntimeException("Mapping for '$owner' has no id")

            val (status, result) = cliHttpClient.deleteWithStatus(
                "$backendUrl/api/github/owner-email-mappings/$id",
                authToken
            )

            if (status !in 200..299) {
                val error = result?.get("error")?.toString() ?: "Backend returned HTTP $status"
                throw RuntimeException(error)
            }

            println("Mapping for '$owner' removed")
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            System.exit(1)
        }
    }
}
