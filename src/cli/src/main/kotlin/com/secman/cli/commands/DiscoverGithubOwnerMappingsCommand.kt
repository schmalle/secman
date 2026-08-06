package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command to auto-discover GitHub owner emails via the GitHub API's
 * public profile field (`GET /users/{owner}`), for already-imported repos
 * with no `ownerEmail` set. Creates a mapping per discovered owner (which
 * backfills matching repos), unless --dry-run is set.
 *
 * Usage:
 *   ./scripts/secman manage-github-owner-mappings discover --dry-run
 *   ./scripts/secman manage-github-owner-mappings discover
 */
@Singleton
@Command(
    name = "discover",
    description = ["Auto-discover owner emails via the GitHub API for repos with no mapped email"],
    mixinStandardHelpOptions = true
)
class DiscoverGithubOwnerMappingsCommand : Runnable {

    @Option(names = ["--dry-run"], description = ["Preview discovered mappings without creating them"])
    var dryRun: Boolean = false

    @ParentCommand
    lateinit var parent: ManageGithubOwnerMappingsCommand

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    override fun run() {
        try {
            println("=".repeat(60))
            println("SecMan GitHub Owner Email Discovery")
            println("=".repeat(60))
            println()

            if (dryRun) {
                println("DRY-RUN MODE: No mappings will be created")
                println()
            }

            val backendUrl = parent.getEffectiveBackendUrl()
            val authToken = cliHttpClient.authenticate(parent.getEffectiveUsername(), parent.getEffectivePassword(), backendUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val result = cliHttpClient.postMap(
                "$backendUrl/api/github/owner-email-mappings/discover",
                mapOf("dryRun" to dryRun),
                authToken
            ) ?: throw RuntimeException("Failed to run owner email discovery - no response from server")

            val status = result["status"]?.toString() ?: "UNKNOWN"
            val ownersEvaluated = (result["ownersEvaluated"] as? Number)?.toInt() ?: 0
            val ownersDiscovered = (result["ownersDiscovered"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val discoveredMappings = (result["discoveredMappings"] as? List<Map<String, Any?>>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val skipped = (result["ownersSkippedNoPublicEmail"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val errors = (result["errors"] as? List<String>) ?: emptyList()

            println("Owners evaluated:   $ownersEvaluated")
            println("Owners discovered:  $ownersDiscovered")
            println()

            if (discoveredMappings.isNotEmpty()) {
                println(if (dryRun) "Would map:" else "Mapped:")
                discoveredMappings.forEach { m ->
                    println("   - ${m["owner"]} -> ${m["email"]} (${m["repoCount"]} repo(s))")
                }
                println()
            }
            if (skipped.isNotEmpty()) {
                println("Skipped (no public email found):")
                skipped.forEach { println("   - $it") }
                println()
            }
            if (errors.isNotEmpty()) {
                println("Errors:")
                errors.forEach { println("   - $it") }
                println()
            }

            when (status) {
                "SUCCESS" -> println("Owner email discovery complete")
                "DRY_RUN" -> println("Dry run complete - no mappings created")
                "PARTIAL_FAILURE" -> println("Discovery completed with some failures")
                "FAILURE" -> println("Owner email discovery failed")
            }

            if (status == "FAILURE" || status == "PARTIAL_FAILURE") {
                System.exit(1)
            }
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            System.exit(1)
        }
    }
}
