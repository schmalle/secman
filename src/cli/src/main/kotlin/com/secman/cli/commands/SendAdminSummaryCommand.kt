package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command to send admin summary email to all ADMIN users via backend HTTP API.
 * Feature: 070-admin-summary-email
 */
@Singleton
@Command(
    name = "send-admin-summary",
    description = ["Send system statistics summary email to all ADMIN users"],
    mixinStandardHelpOptions = true
)
class SendAdminSummaryCommand : Runnable {

    @Option(names = ["--dry-run"], description = ["Preview planned recipients without sending emails"])
    var dryRun: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Detailed logging (show per-recipient status)"])
    var verbose: Boolean = false

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
            println("SecMan Admin Summary Email")
            println("=" .repeat(60))
            println()

            if (dryRun) {
                println("DRY-RUN MODE: No emails will be sent")
                println()
            }

            val effectiveUrl = getEffectiveBackendUrl()
            val effectiveUsername = getEffectiveUsername()
            val effectivePassword = getEffectivePassword()

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            // Gather and display statistics
            val statsResult = cliHttpClient.getMap("$effectiveUrl/api/cli/admin-summary/statistics", authToken)
                ?: throw RuntimeException("Failed to fetch statistics - no response from server")

            val userCount = (statsResult["userCount"] as? Number)?.toLong() ?: 0
            val vulnerabilityCount = (statsResult["vulnerabilityCount"] as? Number)?.toLong() ?: 0
            val assetCount = (statsResult["assetCount"] as? Number)?.toLong() ?: 0
            val vulnerabilityStatisticsUrl = statsResult["vulnerabilityStatisticsUrl"]?.toString() ?: ""

            if (dryRun) {
                println("Statistics to be sent:")
            } else {
                println("Gathering statistics...")
            }
            println("   Users: $userCount")
            println("   Vulnerabilities: $vulnerabilityCount")
            println("   Assets: $assetCount")
            println()

            println("Vulnerability Statistics: $vulnerabilityStatisticsUrl")
            println()

            @Suppress("UNCHECKED_CAST")
            val topProducts = (statsResult["topProducts"] as? List<Map<String, Any?>>) ?: emptyList()
            if (topProducts.isNotEmpty()) {
                println("Top 10 Most Affected Products:")
                topProducts.forEach { product ->
                    val name = product["name"]?.toString() ?: ""
                    val count = (product["vulnerabilityCount"] as? Number)?.toLong() ?: 0
                    println("   $name: $count")
                }
                println()
            }

            @Suppress("UNCHECKED_CAST")
            val topServers = (statsResult["topServers"] as? List<Map<String, Any?>>) ?: emptyList()
            if (topServers.isNotEmpty()) {
                println("Top 10 Most Affected Servers:")
                topServers.forEach { server ->
                    val name = server["name"]?.toString() ?: ""
                    val count = (server["vulnerabilityCount"] as? Number)?.toLong() ?: 0
                    println("   $name: $count")
                }
                println()
            }

            // Execute send
            val sendResult = cliHttpClient.postMap(
                "$effectiveUrl/api/cli/admin-summary/send",
                mapOf("dryRun" to dryRun, "verbose" to verbose),
                authToken
            ) ?: throw RuntimeException("Failed to send admin summary - no response from server")

            val status = sendResult["status"]?.toString() ?: "UNKNOWN"
            val recipientCount = (sendResult["recipientCount"] as? Number)?.toInt() ?: 0
            val emailsSent = (sendResult["emailsSent"] as? Number)?.toInt() ?: 0
            val emailsFailed = (sendResult["emailsFailed"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val recipients = (sendResult["recipients"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val failedRecipients = (sendResult["failedRecipients"] as? List<String>) ?: emptyList()

            if (dryRun) {
                println("Would send to $recipientCount ADMIN users:")
                recipients.forEach { email -> println("   - $email") }
            } else {
                println("Sending to $recipientCount ADMIN users...")
                if (verbose) {
                    recipients.forEach { email -> println("   SUCCESS $email") }
                    failedRecipients.forEach { email -> println("   FAILED $email") }
                }
            }

            println()
            println("=" .repeat(60))
            println("Summary")
            println("=" .repeat(60))
            println("Recipients: $recipientCount")
            println("Emails sent: $emailsSent")
            println("Failures: $emailsFailed")
            println()

            when (status) {
                "SUCCESS" -> println("Admin summary email sent successfully")
                "DRY_RUN" -> println("Dry run complete - no emails sent")
                "PARTIAL_FAILURE" -> {
                    println("Admin summary email completed with some failures")
                    if (verbose && failedRecipients.isNotEmpty()) {
                        println()
                        println("Failed recipients:")
                        failedRecipients.forEach { println("   - $it") }
                    }
                }
                "FAILURE" -> {
                    println("Admin summary email failed")
                    if (recipientCount == 0) {
                        println("No ADMIN users with valid email found")
                    }
                }
            }

            if (status == "FAILURE" || status == "PARTIAL_FAILURE") {
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
