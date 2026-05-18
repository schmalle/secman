package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command to send vulnerability notification emails to users with affected AWS accounts.
 *
 * Identifies AWS accounts with systems having vulnerabilities open longer than N days,
 * maps them to users via UserMapping, and sends each user one consolidated email.
 */
@Singleton
@Command(
    name = "send-notification-users",
    description = ["Send vulnerability notification emails to users with affected AWS accounts"],
    mixinStandardHelpOptions = true
)
class SendNotificationUsersCommand : Runnable {

    @Option(names = ["--dry-run"], description = ["Preview planned notifications without sending emails"])
    var dryRun: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Detailed logging (show per-recipient status)"])
    var verbose: Boolean = false

    @Option(names = ["--days"], description = ["Vulnerability age threshold in days (default: 30)"])
    var thresholdDays: Int = 30

    @Option(names = ["--notification-user"], description = ["Only notify this specific user email (skip all others)"])
    var notificationUser: String? = null

    @Option(names = ["--username"], description = ["Backend username (or set SECMAN_ADMIN_NAME env var)"])
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or set SECMAN_ADMIN_PASS env var)"])
    var password: String? = null

    @Option(names = ["--backend-url"], description = ["Backend API URL (or set SECMAN_BACKEND_URL env var)"])
    var backendUrl: String? = null

    @Spec
    lateinit var spec: Model.CommandSpec

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    override fun run() {
        try {
            println("=".repeat(60))
            println("SecMan User Vulnerability Notifications")
            println("=".repeat(60))
            println()

            if (dryRun) {
                println("DRY-RUN MODE: No emails will be sent")
                println()
            }

            println("Vulnerability age threshold: $thresholdDays days")
            if (notificationUser != null) {
                println("Notification user filter: $notificationUser")
            }
            println()

            val effectiveUrl = getEffectiveBackendUrl()
            val effectiveUsername = getEffectiveUsername()
            val effectivePassword = getEffectivePassword()

            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            val requestBody = mutableMapOf<String, Any>(
                "dryRun" to dryRun,
                "verbose" to verbose,
                "thresholdDays" to thresholdDays
            )
            if (notificationUser != null) {
                requestBody["notificationUser"] = notificationUser!!
            }

            val result = cliHttpClient.postMap(
                "$effectiveUrl/api/cli/user-vulnerability-notifications/send",
                requestBody,
                authToken
            ) ?: throw RuntimeException("Failed to send user vulnerability notifications - no response from server")

            val status = result["status"]?.toString() ?: "UNKNOWN"
            val awsAccountsAffected = (result["awsAccountsAffected"] as? Number)?.toInt() ?: 0
            val usersNotified = (result["usersNotified"] as? Number)?.toInt() ?: 0
            val emailsSent = (result["emailsSent"] as? Number)?.toInt() ?: 0
            val emailsFailed = (result["emailsFailed"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val recipients = (result["recipients"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val failedRecipients = (result["failedRecipients"] as? List<String>) ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val unmappedAccounts = (result["unmappedAccounts"] as? List<String>) ?: emptyList()
            val accountDetails = (result["accountDetails"] as? List<*>) ?: emptyList<Any>()

            println("AWS accounts with overdue vulnerabilities: $awsAccountsAffected")
            println()

            if (dryRun) {
                if (recipients.isNotEmpty()) {
                    println("Would send to $usersNotified users:")
                    recipients.forEach { email -> println("   - $email") }
                }
            } else {
                if (verbose) {
                    recipients.forEach { email -> println("   SUCCESS $email") }
                    failedRecipients.forEach { email -> println("   FAILED $email") }
                }
            }

            printAccountDetails(accountDetails)

            if (unmappedAccounts.isNotEmpty()) {
                println()
                println("AWS accounts without user mapping (${unmappedAccounts.size}):")
                unmappedAccounts.forEach { account -> println("   - $account") }
            }

            println()
            println("=".repeat(60))
            println("Summary")
            println("=".repeat(60))
            println("Threshold:          $thresholdDays days")
            println("AWS accounts:       $awsAccountsAffected")
            println("Users notified:     $usersNotified")
            println("Emails sent:        $emailsSent")
            println("Failures:           $emailsFailed")
            println("Unmapped accounts:  ${unmappedAccounts.size}")
            println()

            when (status) {
                "SUCCESS" -> println("User vulnerability notifications sent successfully")
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
                        println("No users mapped to affected AWS accounts")
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

    private fun printAccountDetails(accountDetails: List<*>) {
        if (accountDetails.isEmpty()) {
            return
        }

        println("Affected AWS account details:")
        accountDetails.forEach { accountEntry ->
            val account = accountEntry as? Map<*, *> ?: return@forEach
            val accountId = account["awsAccountId"]?.toString() ?: "unknown"
            val assets = account["assets"] as? List<*> ?: emptyList<Any>()
            val vulnerabilityCount = assets.sumOf { assetEntry ->
                val asset = assetEntry as? Map<*, *> ?: return@sumOf 0
                (asset["vulnerabilities"] as? List<*>)?.size ?: 0
            }

            println("AWS account $accountId (${assets.size} EC2, $vulnerabilityCount vulnerabilities)")
            assets.forEach { assetEntry ->
                val asset = assetEntry as? Map<*, *> ?: return@forEach
                val assetName = asset["assetName"]?.toString() ?: "unknown"
                val assetId = asset["assetId"]?.toString() ?: "unknown"
                val ip = asset["ip"]?.toString()?.takeIf { it.isNotBlank() } ?: "n/a"
                val vulnerabilities = asset["vulnerabilities"] as? List<*> ?: emptyList<Any>()

                println("   EC2 $assetName (assetId=$assetId, ip=$ip)")
                vulnerabilities.forEach { vulnEntry ->
                    val vulnerability = vulnEntry as? Map<*, *> ?: return@forEach
                    val vulnerabilityId = vulnerability["vulnerabilityId"]?.toString() ?: "unknown"
                    val severity = vulnerability["severity"]?.toString() ?: "UNKNOWN"
                    val ageDays = vulnerability["ageDays"]?.toString() ?: "unknown"
                    val daysOpen = vulnerability["daysOpen"]?.toString()?.takeIf { it.isNotBlank() }
                    val product = vulnerability["vulnerableProductVersions"]?.toString()?.takeIf { it.isNotBlank() }

                    val ageText = if (daysOpen != null) "$daysOpen, age=${ageDays}d" else "age=${ageDays}d"
                    val productText = if (product != null) " [$product]" else ""
                    println("      - $vulnerabilityId ($severity, $ageText)$productText")
                }
            }
        }
        println()
    }
}
