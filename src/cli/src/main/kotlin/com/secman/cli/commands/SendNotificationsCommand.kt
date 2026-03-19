package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command to send email notifications for outdated assets and new vulnerabilities.
 * Communicates with backend via HTTP API.
 * Feature 035: Outdated Asset Notification System
 */
@Singleton
@Command(
    name = "send-notifications",
    description = ["Send email notifications for outdated assets and new vulnerabilities"],
    mixinStandardHelpOptions = true
)
class SendNotificationsCommand : Runnable {

    @Option(names = ["--dry-run"], description = ["Report planned notifications without sending emails"])
    var dryRun: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Detailed logging (show per-asset processing)"])
    var verbose: Boolean = false

    @Option(names = ["--outdated-only"], description = ["Process only outdated asset reminders (skip new vulnerability notifications)"])
    var outdatedOnly: Boolean = false

    @Option(names = ["--username"], description = ["Backend username (or set SECMAN_USERNAME env var)"])
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or set SECMAN_PASSWORD env var)"])
    var password: String? = null

    @Option(names = ["--backend-url"], description = ["Backend API URL (or set SECMAN_BACKEND_URL env var)"])
    var backendUrl: String? = null

    @Spec
    lateinit var spec: Model.CommandSpec

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    override fun run() {
        try {
            println("=" .repeat(60))
            println("SecMan Notification System")
            println("=" .repeat(60))
            println()

            if (dryRun) {
                println("DRY-RUN MODE: No emails will be sent")
                println()
            }

            if (verbose) {
                println("Verbose logging enabled")
                println()
            }

            val effectiveUrl = getEffectiveBackendUrl()
            val effectiveUsername = getEffectiveUsername()
            val effectivePassword = getEffectivePassword()

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            println("Processing outdated asset notifications...")
            val requestBody = mapOf(
                "dryRun" to dryRun,
                "verbose" to verbose
            )

            val result = cliHttpClient.postMap(
                "$effectiveUrl/api/cli/notifications/send-outdated",
                requestBody,
                authToken
            ) ?: throw RuntimeException("Failed to send notifications - no response from server")

            val assetsProcessed = (result["assetsProcessed"] as? Number)?.toInt() ?: 0
            val emailsSent = (result["emailsSent"] as? Number)?.toInt() ?: 0
            val failures = (result["failures"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val skipped = (result["skipped"] as? List<String>) ?: emptyList()

            println()
            println("=" .repeat(60))
            println("Summary")
            println("=" .repeat(60))
            println("Assets processed: $assetsProcessed")
            println("Emails sent: $emailsSent")
            println("Failures: $failures")
            println("Skipped: ${skipped.size}")

            if (verbose && skipped.isNotEmpty()) {
                println()
                println("Skipped recipients:")
                skipped.forEach { println("  - $it") }
            }

            println()
            println("Notification processing complete")

            if (failures > 0) {
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
        return username ?: System.getenv("SECMAN_USERNAME")
            ?: throw IllegalArgumentException("Backend username required. Use --username flag or set SECMAN_USERNAME environment variable")
    }

    private fun getEffectivePassword(): String {
        return password ?: System.getenv("SECMAN_PASSWORD")
            ?: throw IllegalArgumentException("Backend password required. Use --password flag or set SECMAN_PASSWORD environment variable")
    }

    private fun getEffectiveBackendUrl(): String {
        val url = backendUrl ?: System.getenv("SECMAN_HOST") ?: System.getenv("SECMAN_BACKEND_URL") ?: "http://localhost:8080"
        return if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    }
}
