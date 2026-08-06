package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command to import GitHub repositories into secman.
 *
 * The GitHub calls happen server-side using the GitHub App credentials
 * configured under Admin → GitHub App: the backend lists the installation's
 * repositories, counts each repo's open Dependabot alerts (high/critical),
 * upserts the repository inventory and writes one finding snapshot per repo
 * — the history behind `alert-github-repo-owners`.
 */
@Singleton
@Command(
    name = "import-github-repos",
    description = ["Import GitHub repositories (and their open high/critical Dependabot alert counts) via the configured GitHub App"],
    mixinStandardHelpOptions = true
)
class ImportGithubReposCommand : Runnable {

    @Option(names = ["--verbose", "-v"], description = ["Detailed logging (list per-repo errors and disabled repos)"])
    var verbose: Boolean = false

    @Option(names = ["--username"], description = ["Backend username (or set SECMAN_ADMIN_NAME env var)"])
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or set SECMAN_ADMIN_PASS env var)"])
    var password: String? = null

    @Option(names = ["--backend-url"], description = ["Backend API URL (or set SECMAN_HOST / SECMAN_BACKEND_URL env var)"])
    var backendUrl: String? = null

    @Spec
    lateinit var spec: Model.CommandSpec

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    override fun run() {
        try {
            println("=".repeat(60))
            println("SecMan GitHub Repository Import")
            println("=".repeat(60))
            println()

            val effectiveUrl = getEffectiveBackendUrl()
            val effectiveUsername = getEffectiveUsername()
            val effectivePassword = getEffectivePassword()

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val (status, result) = cliHttpClient.postMapWithStatus(
                "$effectiveUrl/api/github/import",
                emptyMap<String, Any>(),
                authToken
            )

            if (status !in 200..299 || result == null) {
                val error = result?.get("error")?.toString() ?: "Backend returned HTTP $status"
                throw RuntimeException(error)
            }

            val discovered = (result["reposDiscovered"] as? Number)?.toInt() ?: 0
            val created = (result["reposNew"] as? Number)?.toInt() ?: 0
            val updated = (result["reposUpdated"] as? Number)?.toInt() ?: 0
            val totalCritical = (result["totalCritical"] as? Number)?.toInt() ?: 0
            val totalHigh = (result["totalHigh"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val alertsDisabled = (result["reposWithAlertsDisabled"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val errors = (result["errors"] as? List<String>) ?: emptyList()

            println("Repositories discovered:  $discovered")
            println("New:                      $created")
            println("Updated:                  $updated")
            println("Open critical alerts:     $totalCritical")
            println("Open high alerts:         $totalHigh")
            println("Alerts disabled/hidden:   ${alertsDisabled.size}")
            println("Errors:                   ${errors.size}")

            if (verbose && alertsDisabled.isNotEmpty()) {
                println()
                println("Repositories with Dependabot alerts disabled or inaccessible:")
                alertsDisabled.forEach { println("   - $it") }
            }
            if (errors.isNotEmpty()) {
                println()
                println("Errors:")
                errors.forEach { println("   - $it") }
            }

            println()
            if (errors.isEmpty()) {
                println("GitHub repository import completed successfully")
            } else {
                println("GitHub repository import completed with errors")
                System.exit(1)
            }

        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            System.exit(1)
        }
    }

    private fun getEffectiveUsername(): String {
        return username ?: System.getenv("SECMAN_ADMIN_NAME")
            ?: throw IllegalArgumentException("Backend username required. Use --username flag or set SECMAN_ADMIN_NAME environment variable")
    }

    private fun getEffectivePassword(): String {
        return password ?: System.getenv("SECMAN_ADMIN_PASS")
            ?: throw IllegalArgumentException("Backend password required. Use --password flag or set SECMAN_ADMIN_PASS environment variable")
    }

    private fun getEffectiveBackendUrl(): String {
        val url = backendUrl ?: System.getenv("SECMAN_HOST") ?: System.getenv("SECMAN_BACKEND_URL") ?: "http://localhost:8080"
        return if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    }
}
