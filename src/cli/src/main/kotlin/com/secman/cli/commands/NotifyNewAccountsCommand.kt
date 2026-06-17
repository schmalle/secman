package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.io.File

/**
 * CLI command that notifies users about new AWS account mappings created via import
 * within a configurable look-back window (default: last 24 hours).
 *
 * "New account" means a UserMapping row whose aws_account_id is non-null and whose
 * created_at timestamp falls inside the window. Users with at least one such mapping
 * receive one consolidated email listing all their newly-mapped account IDs.
 *
 * The email notification body text is read from a local file supplied via --file (-f).
 * This allows operators to customise the message (e.g. with data-classification
 * instructions, contact details, or regulatory context) without redeploying.
 * The list of new account IDs is always appended below the custom text.
 */
@Singleton
@Command(
    name = "notify-new-accounts",
    description = ["Notify users about new AWS account mappings created in the last N hours"],
    mixinStandardHelpOptions = true
)
class NotifyNewAccountsCommand : Runnable {

    @Option(
        names = ["-f", "--file"],
        required = true,
        description = ["Path to a text file whose content is used as the email notification body"]
    )
    lateinit var notificationFile: String

    @Option(
        names = ["--hours"],
        description = ["Look-back window in hours: notify about mappings created within this period (default: 24)"]
    )
    var hours: Int = 24

    @Option(names = ["--dry-run"], description = ["Preview planned notifications without sending emails"])
    var dryRun: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Detailed logging (show per-recipient status)"])
    var verbose: Boolean = false

    @Option(names = ["--username"], description = ["Backend username (or set SECMAN_ADMIN_NAME env var)"])
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or set SECMAN_ADMIN_PASS env var)"])
    var password: String? = null

    @Option(names = ["--backend-url"], description = ["Backend API URL (or set SECMAN_HOST / SECMAN_BACKEND_URL env var)"])
    var backendUrl: String? = null

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    override fun run() {
        try {
            println("=".repeat(60))
            println("SecMan New Account Notifications")
            println("=".repeat(60))
            println()

            if (hours < 1) {
                System.err.println("Error: --hours must be >= 1")
                System.exit(2)
                return
            }

            val file = File(notificationFile)
            if (!file.exists() || !file.isFile) {
                System.err.println("Error: notification file not found: $notificationFile")
                System.exit(2)
                return
            }
            val notificationText = file.readText(Charsets.UTF_8).trim()
            if (notificationText.isBlank()) {
                System.err.println("Error: notification file is empty: $notificationFile")
                System.exit(2)
                return
            }

            if (dryRun) {
                println("DRY-RUN MODE: No emails will be sent")
                println()
            }

            println("Notification file:  $notificationFile")
            println("Look-back window:   $hours hour(s)")
            println()

            val effectiveUrl = getEffectiveBackendUrl()
            val effectiveUsername = getEffectiveUsername()
            val effectivePassword = getEffectivePassword()

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val requestBody = mapOf(
                "dryRun" to dryRun,
                "verbose" to verbose,
                "hours" to hours,
                "notificationText" to notificationText
            )

            val result = cliHttpClient.postMap(
                "$effectiveUrl/api/cli/new-account-notifications/send",
                requestBody,
                authToken
            ) ?: throw RuntimeException("Failed to send new-account notifications — no response from server")

            val status = result["status"]?.toString() ?: "UNKNOWN"
            val accountMappingsFound = (result["accountMappingsFound"] as? Number)?.toInt() ?: 0
            val usersNotified = (result["usersNotified"] as? Number)?.toInt() ?: 0
            val emailsSent = (result["emailsSent"] as? Number)?.toInt() ?: 0
            val emailsFailed = (result["emailsFailed"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val recipients = (result["recipients"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val failedRecipients = (result["failedRecipients"] as? List<String>) ?: emptyList()

            println("New AWS account mappings found in last $hours hour(s): $accountMappingsFound")
            println()

            if (dryRun) {
                if (recipients.isNotEmpty()) {
                    println("Would notify $usersNotified user(s):")
                    recipients.forEach { email -> println("   - $email") }
                } else {
                    println("No new AWS account mappings found in the last $hours hour(s).")
                }
            } else if (verbose) {
                recipients.forEach { email -> println("   SUCCESS $email") }
                failedRecipients.forEach { email -> println("   FAILED  $email") }
            }

            println()
            println("=".repeat(60))
            println("Summary")
            println("=".repeat(60))
            println("Look-back window:       $hours hour(s)")
            println("New account mappings:   $accountMappingsFound")
            println("Users notified:         $usersNotified")
            println("Emails sent:            $emailsSent")
            println("Failures:               $emailsFailed")
            println()

            when (status) {
                "SUCCESS" -> println("New-account notifications sent successfully")
                "DRY_RUN" -> println("Dry run complete — no emails sent")
                "PARTIAL_FAILURE" -> {
                    println("Notifications completed with some failures")
                    if (verbose && failedRecipients.isNotEmpty()) {
                        println()
                        println("Failed recipients:")
                        failedRecipients.forEach { println("   - $it") }
                    }
                }
                "FAILURE" -> {
                    println("Notification sending failed")
                    if (accountMappingsFound == 0) {
                        println("No new AWS account mappings found in the last $hours hour(s)")
                    }
                }
            }

            if (status == "FAILURE" || status == "PARTIAL_FAILURE") {
                System.exit(1)
            }

        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) e.printStackTrace()
            System.exit(1)
        }
    }

    private fun getEffectiveUsername(): String =
        username ?: System.getenv("SECMAN_ADMIN_NAME")
            ?: throw IllegalArgumentException("Backend username required. Use --username or set SECMAN_ADMIN_NAME")

    private fun getEffectivePassword(): String =
        password ?: System.getenv("SECMAN_ADMIN_PASS")
            ?: throw IllegalArgumentException("Backend password required. Use --password or set SECMAN_ADMIN_PASS")

    private fun getEffectiveBackendUrl(): String {
        val url = backendUrl ?: System.getenv("SECMAN_HOST") ?: System.getenv("SECMAN_BACKEND_URL") ?: "http://localhost:8080"
        return if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    }
}
