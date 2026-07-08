package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command to alert GitHub repo owners whose open high+critical
 * Dependabot alert count has NOT decreased over the last --days days
 * (default 30, comparing against the newest import snapshot that is at
 * least that old).
 *
 * Repos with an active alert exception are skipped; repos without an
 * owner email are reported as unmapped; repos without a sufficiently old
 * snapshot are reported as skipped. One consolidated email is sent per
 * owner. Pure backend-DB operation — run `import-github-repos` first.
 */
@Singleton
@Command(
    name = "alert-github-repo-owners",
    description = ["Alert GitHub repo owners whose open high/critical vulnerability count has not decreased in the last N days"],
    mixinStandardHelpOptions = true
)
class AlertGithubRepoOwnersCommand : Runnable {

    @Option(names = ["--dry-run"], description = ["Preview planned alerts without sending emails"])
    var dryRun: Boolean = false

    @Option(names = ["--days"], description = ["Comparison window in days (default: 30)"])
    var thresholdDays: Int = 30

    @Option(names = ["--verbose", "-v"], description = ["Detailed logging (show per-recipient status)"])
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
            println("SecMan GitHub Repo Owner Alerts")
            println("=".repeat(60))
            println()

            if (thresholdDays < 1) {
                System.err.println("Error: --days must be >= 1")
                System.exit(2)
                return
            }
            if (dryRun) {
                println("DRY-RUN MODE: No emails will be sent")
                println()
            }
            println("Comparison window:  $thresholdDays days")
            println()

            val effectiveUrl = getEffectiveBackendUrl()
            val effectiveUsername = getEffectiveUsername()
            val effectivePassword = getEffectivePassword()

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val requestBody = mapOf(
                "dryRun" to dryRun,
                "thresholdDays" to thresholdDays
            )

            val result = cliHttpClient.postMap(
                "$effectiveUrl/api/cli/github-repo-alerts/send",
                requestBody,
                authToken
            ) ?: throw RuntimeException("Failed to send GitHub repo alerts - no response from server")

            val status = result["status"]?.toString() ?: "UNKNOWN"
            val reposEvaluated = (result["reposEvaluated"] as? Number)?.toInt() ?: 0
            val reposAlerted = (result["reposAlerted"] as? Number)?.toInt() ?: 0
            val emailsSent = (result["emailsSent"] as? Number)?.toInt() ?: 0
            val emailsFailed = (result["emailsFailed"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val reposExcepted = (result["reposExcepted"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val skippedHistory = (result["reposSkippedInsufficientHistory"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val unmappedRepos = (result["unmappedRepos"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val recipients = (result["recipients"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val failedRecipients = (result["failedRecipients"] as? List<String>) ?: emptyList()

            println("Repositories evaluated:                $reposEvaluated")
            println("Repositories with non-decreasing vulns: $reposAlerted")
            println()

            if (reposExcepted.isNotEmpty()) {
                println("Excepted repositories (skipped):")
                reposExcepted.forEach { println("   - $it") }
                println()
            }
            if (unmappedRepos.isNotEmpty()) {
                println("Unmapped repositories (no owner email — nobody alerted):")
                unmappedRepos.forEach { println("   - $it") }
                println()
            }
            if (skippedHistory.isNotEmpty()) {
                println("Skipped (no snapshot at least $thresholdDays days old yet):")
                skippedHistory.forEach { println("   - $it") }
                println()
            }
            if (verbose || dryRun) {
                if (recipients.isNotEmpty() || dryRun) {
                    println(if (dryRun) "Would alert:" else "Alerted:")
                    recipients.forEach { println("   - $it") }
                    println()
                }
                failedRecipients.forEach { println("   FAILED $it") }
            }

            println("=".repeat(60))
            println("Summary")
            println("=".repeat(60))
            println("Window:            $thresholdDays days")
            println("Repos alerted:     $reposAlerted")
            println("Emails sent:       $emailsSent")
            println("Failures:          $emailsFailed")
            println("Excepted:          ${reposExcepted.size}")
            println("Unmapped:          ${unmappedRepos.size}")
            println("Skipped (history): ${skippedHistory.size}")
            println()

            when (status) {
                "SUCCESS" -> println("GitHub repo alerts sent successfully")
                "DRY_RUN" -> println("Dry run complete - no emails sent")
                "PARTIAL_FAILURE" -> println("Alerts completed with some failures")
                "FAILURE" -> println("Alert sending failed")
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
