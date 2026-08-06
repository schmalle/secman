package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command: email the "accounts with the longest-open findings" report to all ADMIN users.
 *
 * Spec: docs/superpowers/specs/2026-07-26-account-finding-age-design.md
 */
@Singleton
@Command(
    name = "send-account-finding-age-report",
    description = ["Email the top AWS accounts with the longest-open findings to all ADMIN users"],
    mixinStandardHelpOptions = true
)
class SendAccountFindingAgeReportCommand : Runnable {

    @Option(names = ["--limit"], description = ["Number of accounts to report (1-50, default: 10)"])
    var limit: Int = 10

    @Option(names = ["--dry-run"], description = ["Preview planned recipients without sending emails"])
    var dryRun: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Detailed logging (show per-recipient status)"])
    var verbose: Boolean = false

    @Option(names = ["--username"], description = ["Backend username (or set SECMAN_ADMIN_NAME env var)"])
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or set SECMAN_ADMIN_PASS env var)"])
    var password: String? = null

    @Option(names = ["--backend-url"], description = ["Backend API URL (or set SECMAN_BACKEND_URL env var)"])
    var backendUrl: String? = null

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    /**
     * Test seam: production always exits the real JVM via System.exit; unit tests replace
     * this with a recorder so run() can be exercised end-to-end (including the exception and
     * FAILURE/PARTIAL_FAILURE paths) without terminating the test process.
     */
    var exitAction: (Int) -> Unit = { code -> System.exit(code) }

    override fun run() {
        try {
            println("=".repeat(60))
            println("SecMan Account Finding-Age Report")
            println("=".repeat(60))
            println()

            if (dryRun) {
                println("DRY-RUN MODE: No emails will be sent")
                println()
            }

            val effectiveUrl = getEffectiveBackendUrl()
            val authToken = cliHttpClient.authenticate(getEffectiveUsername(), getEffectivePassword(), effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val (httpStatus, sendResult) = cliHttpClient.postMapWithStatus(
                "$effectiveUrl/api/cli/account-finding-age-report/send",
                mapOf("limit" to limit, "dryRun" to dryRun, "verbose" to verbose),
                authToken
            )

            if (httpStatus !in 200..299 || sendResult == null) {
                throw RuntimeException(describeError(httpStatus, sendResult, effectiveUrl))
            }

            val status = sendResult["status"]?.toString() ?: "UNKNOWN"
            val recipientCount = (sendResult["recipientCount"] as? Number)?.toInt() ?: 0
            val emailsSent = (sendResult["emailsSent"] as? Number)?.toInt() ?: 0
            val emailsFailed = (sendResult["emailsFailed"] as? Number)?.toInt() ?: 0
            val accountCount = (sendResult["accountCount"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val recipients = (sendResult["recipients"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val failedRecipients = (sendResult["failedRecipients"] as? List<String>) ?: emptyList()

            println("Accounts in report: $accountCount")
            if (accountCount == 0) {
                println("No accounts with open findings - nothing to send")
            }
            println()

            if (dryRun) {
                println("Would send to $recipientCount ADMIN users:")
                recipients.forEach { println("   - $it") }
            } else if (verbose) {
                recipients.forEach { println("   SUCCESS $it") }
                failedRecipients.forEach { println("   FAILED $it") }
            }

            println()
            println("=".repeat(60))
            println("Summary")
            println("=".repeat(60))
            println("Recipients: $recipientCount")
            println("Emails sent: $emailsSent")
            println("Failures: $emailsFailed")
            println()

            when (status) {
                "SUCCESS" -> println("Account finding-age report processed successfully")
                "DRY_RUN" -> println("Dry run complete - no emails sent")
                "PARTIAL_FAILURE" -> println("Report completed with some failures")
                "FAILURE" -> {
                    println("Report failed")
                    if (recipientCount == 0) println("No ADMIN users with valid email found")
                }
            }

            val exitCode = determineExitCode(status)
            if (exitCode != null) {
                exitAction(exitCode)
            }

        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) e.printStackTrace()
            exitAction(2)
        }
    }

    /**
     * Turns a failed HTTP response into the message shown to the user. A genuine
     * connectivity failure (CliHttpClient.postMapWithStatus signals this with status -1
     * and a null body) is reported distinctly from a backend-returned error so the two
     * are never confused with each other or with "no response from server".
     */
    fun describeError(status: Int, body: Map<*, *>?, backendUrl: String): String {
        if (status == -1) {
            return "Failed to reach backend at $backendUrl (connection error). " +
                "Check network connectivity and the --backend-url/SECMAN_HOST value."
        }
        return body?.get("message")?.toString()
            ?: body?.get("error")?.toString()
            ?: "Backend returned HTTP $status"
    }

    /** exit-code-2 (not 1) is deliberate for this command; see task-6 brief. */
    fun determineExitCode(status: String): Int? =
        if (status == "FAILURE" || status == "PARTIAL_FAILURE") 2 else null

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
