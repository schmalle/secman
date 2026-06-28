package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import jakarta.inject.Inject
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * CLI command to send vulnerability notification emails for GitHub repositories
 * (Dependabot alerts). The non-AWS counterpart to `send-notification-users`:
 * recipients are resolved by workgroup membership of the repository asset
 * (and the asset owner when it is a real user email).
 */
@Singleton
@Command(
    name = "send-repository-notifications",
    description = ["Send Dependabot vulnerability notification emails for repositories"],
    mixinStandardHelpOptions = true
)
class SendRepositoryNotificationsCommand : Runnable {

    @Option(names = ["--dry-run"], description = ["Preview planned notifications without sending emails"])
    var dryRun: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Detailed logging (show per-recipient status)"])
    var verbose: Boolean = false

    @Option(names = ["--days"], description = ["Vulnerability age threshold in days (default: 30)"])
    var thresholdDays: Int = 30

    @Option(names = ["--notification-user"], description = ["Only notify this specific user email (skip all others)"])
    var notificationUser: String? = null

    @Option(names = ["--email-prefix"], description = ["Only notify users whose email starts with this prefix"])
    var emailPrefix: String? = null

    @Option(names = ["--username"], description = ["Backend username (or set SECMAN_ADMIN_NAME env var)"])
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or set SECMAN_ADMIN_PASS env var)"])
    var password: String? = null

    @Option(names = ["--backend-url"], description = ["Backend API URL (or set SECMAN_BACKEND_URL env var)"])
    var backendUrl: String? = null

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    override fun run() {
        try {
            println("=".repeat(60))
            println("SecMan Repository Vulnerability Notifications")
            println("=".repeat(60))
            println()

            if (dryRun) {
                println("DRY-RUN MODE: No emails will be sent")
                println()
            }
            println("Vulnerability age threshold: $thresholdDays days")
            if (notificationUser != null) println("Notification user filter: $notificationUser")
            if (emailPrefix != null) println("Email prefix filter: $emailPrefix")
            println()

            val effectiveUrl = getEffectiveBackendUrl()
            val authToken = cliHttpClient.authenticate(getEffectiveUsername(), getEffectivePassword(), effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val requestBody = mutableMapOf<String, Any>(
                "dryRun" to dryRun,
                "verbose" to verbose,
                "thresholdDays" to thresholdDays
            )
            notificationUser?.let { requestBody["notificationUser"] = it }
            emailPrefix?.let { requestBody["emailPrefix"] = it }

            val result = cliHttpClient.postMap(
                "$effectiveUrl/api/cli/repository-vulnerability-notifications/send",
                requestBody,
                authToken
            ) ?: throw RuntimeException("Failed to send repository vulnerability notifications - no response from server")

            val status = result["status"]?.toString() ?: "UNKNOWN"
            val notificationUserExists = result["notificationUserExists"] as? Boolean
            val repositoriesAffected = (result["repositoriesAffected"] as? Number)?.toInt() ?: 0
            val usersNotified = (result["usersNotified"] as? Number)?.toInt() ?: 0
            val emailsSent = (result["emailsSent"] as? Number)?.toInt() ?: 0
            val emailsFailed = (result["emailsFailed"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val recipients = (result["recipients"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val failedRecipients = (result["failedRecipients"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val unmappedRepositories = (result["unmappedRepositories"] as? List<String>) ?: emptyList()
            val repositoryDetails = (result["repositoryDetails"] as? List<*>) ?: emptyList<Any>()

            if (notificationUser != null && notificationUserExists == false) {
                println()
                println("WARNING: No user account exists with email '$notificationUser'.")
                println("No notifications were sent. Verify the --notification-user value.")
                System.exit(2)
            }

            println("Repositories with overdue vulnerabilities: $repositoriesAffected")
            println()

            if (dryRun) {
                if (recipients.isNotEmpty()) {
                    println("Would send to $usersNotified users:")
                    recipients.forEach { println("   - $it") }
                }
            } else if (verbose) {
                recipients.forEach { println("   SUCCESS $it") }
                failedRecipients.forEach { println("   FAILED $it") }
            }

            printRepositoryDetails(repositoryDetails)

            if (unmappedRepositories.isNotEmpty()) {
                println()
                println("Repositories without recipients (${unmappedRepositories.size}):")
                unmappedRepositories.forEach { println("   - $it") }
            }

            println()
            println("=".repeat(60))
            println("Summary")
            println("=".repeat(60))
            println("Threshold:            $thresholdDays days")
            println("Repositories:         $repositoriesAffected")
            println("Users notified:       $usersNotified")
            println("Emails sent:          $emailsSent")
            println("Failures:             $emailsFailed")
            println("Unmapped repos:       ${unmappedRepositories.size}")
            println()

            when (status) {
                "SUCCESS" -> println("Repository vulnerability notifications sent successfully")
                "DRY_RUN" -> println("Dry run complete - no emails sent")
                "PARTIAL_FAILURE" -> println("Notifications completed with some failures")
                "FAILURE" -> println("Notification sending failed")
            }

            if (status == "FAILURE" || status == "PARTIAL_FAILURE") System.exit(1)
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) e.printStackTrace()
            System.exit(1)
        }
    }

    private fun printRepositoryDetails(details: List<*>) {
        if (details.isEmpty()) return
        println("Affected repositories:")
        details.forEach { entry ->
            val repo = entry as? Map<*, *> ?: return@forEach
            val name = repo["repositoryName"]?.toString() ?: "unknown"
            val vulnCount = (repo["vulnCount"] as? Number)?.toInt() ?: 0
            val critical = (repo["criticalCount"] as? Number)?.toInt() ?: 0
            val high = (repo["highCount"] as? Number)?.toInt() ?: 0
            val oldest = (repo["oldestVulnDays"] as? Number)?.toInt() ?: 0
            println("   $name ($vulnCount vulns: $critical critical, $high high; oldest ${oldest}d)")
        }
        println()
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
