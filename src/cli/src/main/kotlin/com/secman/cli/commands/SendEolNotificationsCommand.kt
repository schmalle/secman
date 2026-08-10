package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import jakarta.inject.Inject
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Model
import picocli.CommandLine.Option
import picocli.CommandLine.Spec

/**
 * Emails account owners about software and operating systems on their systems
 * that reach end of life within the next N months (default 12).
 *
 * Recipients are resolved exactly like the other owner-facing mails: the AWS
 * account's owners, workgroup members and sharing targets, falling back to the
 * asset's own owner. Run `eol-sync` first — this command reads stored findings
 * and performs no matching of its own.
 */
@Singleton
@Command(
    name = "send-eol-notifications",
    description = ["Email account owners about software/OS reaching end of life within the next N months"],
    mixinStandardHelpOptions = true
)
class SendEolNotificationsCommand : Runnable {

    @Option(names = ["--months"], description = ["Look-ahead window in months, 1-60 (default: 12)"])
    var months: Int = 12

    @Option(names = ["--dry-run"], description = ["Preview planned recipients without sending emails"])
    var dryRun: Boolean = false

    @Option(names = ["--include-already-eol"], description = ["Also report components that are already past EOL"])
    var includeAlreadyEol: Boolean = false

    @Option(names = ["--only-email"], description = ["Only notify this address (case-insensitive)"])
    var onlyEmail: String? = null

    @Option(names = ["--verbose", "-v"], description = ["Per-recipient delivery status"])
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
            println("SecMan EOL Owner Notifications")
            println("=".repeat(60))
            println()

            if (months < 1 || months > 60) {
                System.err.println("Error: --months must be between 1 and 60")
                System.exit(2)
                return
            }
            if (dryRun) {
                println("DRY-RUN MODE: No emails will be sent")
                println()
            }
            println("Look-ahead window: $months months")
            if (includeAlreadyEol) println("Including components already past EOL")
            if (onlyEmail != null) println("Restricting to recipient: $onlyEmail")
            println()

            val effectiveUrl = getEffectiveBackendUrl()
            val authToken = cliHttpClient.authenticate(getEffectiveUsername(), getEffectivePassword(), effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val requestBody = mapOf(
                "months" to months,
                "dryRun" to dryRun,
                "onlyEmail" to onlyEmail,
                "includeAlreadyEol" to includeAlreadyEol
            )

            val result = cliHttpClient.postMap("$effectiveUrl/api/eol/notifications/send", requestBody, authToken)
                ?: throw RuntimeException("EOL notification run failed - no response from server")

            val status = result["status"]?.toString() ?: "UNKNOWN"
            val findingsConsidered = intOf(result["findingsConsidered"])
            val recipientsResolved = intOf(result["recipientsResolved"])
            val emailsSent = intOf(result["emailsSent"])
            val emailsFailed = intOf(result["emailsFailed"])
            @Suppress("UNCHECKED_CAST")
            val unmappedOwners = (result["unmappedOwners"] as? List<Any?>)?.map { it.toString() } ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val recipients = (result["recipients"] as? List<Map<String, Any?>>) ?: emptyList()

            println("Components in window: $findingsConsidered")
            println("Recipients resolved:  $recipientsResolved")
            println()

            if (verbose || dryRun) {
                if (recipients.isNotEmpty()) {
                    println(if (dryRun) "Would notify:" else "Notified:")
                    recipients.forEach { recipient ->
                        val email = recipient["email"]?.toString() ?: "?"
                        val componentCount = intOf(recipient["componentCount"])
                        val assetCount = intOf(recipient["assetCount"])
                        val sent = recipient["sent"] == true
                        val marker = when {
                            dryRun -> " "
                            sent -> "OK"
                            else -> "FAILED"
                        }
                        println("   [$marker] $email — $componentCount component(s) on $assetCount system(s)")
                    }
                    println()
                }
            }
            if (unmappedOwners.isNotEmpty()) {
                println("Owners that could not be mapped to an email address:")
                unmappedOwners.forEach { println("   - $it") }
                println()
            }

            println("=".repeat(60))
            println("Summary")
            println("=".repeat(60))
            println("Emails sent:   $emailsSent")
            println("Failures:      $emailsFailed")
            println()

            when (status) {
                "SUCCESS" -> println(if (dryRun) "Dry run complete - no emails sent" else "EOL notifications sent successfully")
                "PARTIAL" -> println("EOL notifications completed with some failures")
                else -> println("EOL notification run failed")
            }

            if (status != "SUCCESS") {
                System.exit(1)
            }
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) e.printStackTrace()
            System.exit(1)
        }
    }

    private fun intOf(value: Any?): Int = (value as? Number)?.toInt() ?: 0

    private fun getEffectiveUsername(): String =
        username ?: System.getenv("SECMAN_ADMIN_NAME")
        ?: throw IllegalArgumentException("Backend username required. Use --username flag or set SECMAN_ADMIN_NAME environment variable")

    private fun getEffectivePassword(): String =
        password ?: System.getenv("SECMAN_ADMIN_PASS")
        ?: throw IllegalArgumentException("Backend password required. Use --password flag or set SECMAN_ADMIN_PASS environment variable")

    private fun getEffectiveBackendUrl(): String {
        val url = backendUrl ?: System.getenv("SECMAN_HOST") ?: System.getenv("SECMAN_BACKEND_URL") ?: "http://localhost:8080"
        return if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    }
}
