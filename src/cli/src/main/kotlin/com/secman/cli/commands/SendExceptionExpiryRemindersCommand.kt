package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command that reminds vulnerability exception owners when their exception is
 * expiring soon (default: exactly 7 days from today).
 *
 * "Owner" is the exception's createdBy username, resolved to an email address on the
 * backend. Owners with one or more expiring exceptions receive one consolidated email
 * listing all of them. A reminder is sent once per (exception, expiration date) pair —
 * repeated runs never re-notify for the same expiration date.
 */
@Singleton
@Command(
    name = "send-exception-expiry-reminders",
    description = ["Notify vulnerability exception owners about exceptions expiring soon"],
    mixinStandardHelpOptions = true
)
class SendExceptionExpiryRemindersCommand : Runnable {

    @Option(
        names = ["--days"],
        description = ["Remind about exceptions expiring exactly this many days from today (default: 7)"]
    )
    var days: Int = 7

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
            println("SecMan Vulnerability Exception Expiry Reminders")
            println("=".repeat(60))
            println()

            if (days < 1) {
                System.err.println("Error: --days must be >= 1")
                System.exit(2)
                return
            }

            if (dryRun) {
                println("DRY-RUN MODE: No emails will be sent")
                println()
            }

            println("Reminder window:    exceptions expiring in $days day(s)")
            println()

            val effectiveUrl = getEffectiveBackendUrl()
            val effectiveUsername = getEffectiveUsername()
            val effectivePassword = getEffectivePassword()

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val requestBody = mapOf(
                "dryRun" to dryRun,
                "verbose" to verbose,
                "days" to days
            )

            val result = cliHttpClient.postMap(
                "$effectiveUrl/api/cli/vulnerability-exception-expiry-notifications/send",
                requestBody,
                authToken
            ) ?: throw RuntimeException("Failed to send exception expiry reminders — no response from server")

            val status = result["status"]?.toString() ?: "UNKNOWN"
            val exceptionsExpiring = (result["exceptionsExpiring"] as? Number)?.toInt() ?: 0
            val ownersNotified = (result["ownersNotified"] as? Number)?.toInt() ?: 0
            val emailsSent = (result["emailsSent"] as? Number)?.toInt() ?: 0
            val emailsFailed = (result["emailsFailed"] as? Number)?.toInt() ?: 0
            val alreadyNotified = (result["alreadyNotified"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val recipients = (result["recipients"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val failedRecipients = (result["failedRecipients"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val unmappedOwners = (result["unmappedOwners"] as? List<String>) ?: emptyList()

            println("Exceptions expiring in $days day(s): $exceptionsExpiring")
            println("Already reminded (skipped):          $alreadyNotified")
            println()

            if (dryRun) {
                if (recipients.isNotEmpty()) {
                    println("Would notify $ownersNotified owner(s):")
                    recipients.forEach { email -> println("   - $email") }
                } else {
                    println("No exception owners to notify for a $days-day window.")
                }
            } else if (verbose) {
                recipients.forEach { email -> println("   SUCCESS $email") }
                failedRecipients.forEach { email -> println("   FAILED  $email") }
            }

            if (unmappedOwners.isNotEmpty()) {
                println()
                println("Owners with no resolvable email (skipped):")
                unmappedOwners.forEach { println("   - $it") }
            }

            println()
            println("=".repeat(60))
            println("Summary")
            println("=".repeat(60))
            println("Reminder window:        $days day(s)")
            println("Exceptions expiring:    $exceptionsExpiring")
            println("Owners notified:        $ownersNotified")
            println("Emails sent:            $emailsSent")
            println("Failures:               $emailsFailed")
            println()

            when (status) {
                "SUCCESS" -> println("Exception expiry reminders sent successfully")
                "DRY_RUN" -> println("Dry run complete — no emails sent")
                "PARTIAL_FAILURE" -> {
                    println("Reminders completed with some failures")
                    if (verbose && failedRecipients.isNotEmpty()) {
                        println()
                        println("Failed recipients:")
                        failedRecipients.forEach { println("   - $it") }
                    }
                }
                "FAILURE" -> {
                    println("Reminder sending failed")
                    if (exceptionsExpiring == 0) {
                        println("No exceptions expiring in $days day(s)")
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
