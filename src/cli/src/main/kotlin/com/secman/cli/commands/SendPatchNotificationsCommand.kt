package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command to notify users about missing patches (overdue vulnerabilities),
 * restricted to users whose login email starts with a given first character.
 *
 * The mandatory positional argument is the first character of the email address
 * (e.g. "a" → every user whose email begins with 'a'). This lets operators send
 * patch reminders in deterministic alphabetical batches.
 *
 * Reuses the user-vulnerability-notification pipeline: it finds AWS accounts with
 * vulnerabilities open longer than --days, maps them to users via UserMapping, then
 * keeps only recipients whose email matches the prefix before sending one
 * consolidated email per user.
 */
@Singleton
@Command(
    name = "send-patch-notifications",
    description = ["Notify users about missing patches, filtered by the first character of their email address"],
    mixinStandardHelpOptions = true
)
class SendPatchNotificationsCommand : Runnable {

    @Parameters(
        index = "0",
        paramLabel = "<emailPrefix>",
        description = ["First character of the email address to notify (e.g. 'a'). Required."]
    )
    var emailPrefix: String = ""

    @Option(names = ["--dry-run"], description = ["Preview planned notifications without sending emails"])
    var dryRun: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Detailed logging (show per-recipient status)"])
    var verbose: Boolean = false

    @Option(names = ["--days"], description = ["Vulnerability (missing patch) age threshold in days (default: 30)"])
    var thresholdDays: Int = 30

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
            println("SecMan Patch Notifications")
            println("=".repeat(60))
            println()

            val prefix = emailPrefix.trim()
            if (prefix.isEmpty()) {
                System.err.println("Error: the email prefix character is required (e.g. 'a').")
                System.err.println("Usage: secman send-patch-notifications <emailPrefix> [--days N] [--dry-run]")
                System.exit(2)
                return
            }

            if (dryRun) {
                println("DRY-RUN MODE: No emails will be sent")
                println()
            }

            println("Email prefix filter:          $prefix")
            println("Missing-patch age threshold:  $thresholdDays days")
            println()

            val effectiveUrl = getEffectiveBackendUrl()
            val effectiveUsername = getEffectiveUsername()
            val effectivePassword = getEffectivePassword()

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val requestBody = mapOf(
                "dryRun" to dryRun,
                "verbose" to verbose,
                "thresholdDays" to thresholdDays,
                "emailPrefix" to prefix
            )

            val result = cliHttpClient.postMap(
                "$effectiveUrl/api/cli/user-vulnerability-notifications/send",
                requestBody,
                authToken
            ) ?: throw RuntimeException("Failed to send patch notifications - no response from server")

            val status = result["status"]?.toString() ?: "UNKNOWN"
            val awsAccountsAffected = (result["awsAccountsAffected"] as? Number)?.toInt() ?: 0
            val usersNotified = (result["usersNotified"] as? Number)?.toInt() ?: 0
            val emailsSent = (result["emailsSent"] as? Number)?.toInt() ?: 0
            val emailsFailed = (result["emailsFailed"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val recipients = (result["recipients"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val failedRecipients = (result["failedRecipients"] as? List<String>) ?: emptyList()

            println("AWS accounts with overdue vulnerabilities: $awsAccountsAffected")
            println()

            if (dryRun) {
                if (recipients.isNotEmpty()) {
                    println("Would notify $usersNotified users matching '$prefix*':")
                    recipients.forEach { email -> println("   - $email") }
                } else {
                    println("No users matching '$prefix*' have overdue vulnerabilities.")
                }
            } else if (verbose) {
                recipients.forEach { email -> println("   SUCCESS $email") }
                failedRecipients.forEach { email -> println("   FAILED $email") }
            }

            println()
            println("=".repeat(60))
            println("Summary")
            println("=".repeat(60))
            println("Email prefix:       $prefix")
            println("Threshold:          $thresholdDays days")
            println("AWS accounts:       $awsAccountsAffected")
            println("Users notified:     $usersNotified")
            println("Emails sent:        $emailsSent")
            println("Failures:           $emailsFailed")
            println()

            when (status) {
                "SUCCESS" -> println("Patch notifications sent successfully")
                "DRY_RUN" -> println("Dry run complete - no emails sent")
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
                    if (awsAccountsAffected == 0) {
                        println("No AWS accounts found with vulnerabilities older than $thresholdDays days")
                    } else if (usersNotified == 0) {
                        println("No users matching '$prefix*' mapped to affected AWS accounts")
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
