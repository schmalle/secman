package com.secman.cli.commands

import com.secman.cli.service.AdminSummaryCliService
import com.secman.domain.ExecutionStatus
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CLI command to send admin summary email to all ADMIN users.
 * Feature: 070-admin-summary-email
 *
 * Usage:
 *   ./bin/secman send-admin-summary
 *   ./bin/secman send-admin-summary --dry-run
 *   ./bin/secman send-admin-summary --verbose
 *   ./bin/secman send-admin-summary --dry-run --verbose
 */
@Singleton
@Command(
    name = "send-admin-summary",
    description = ["Send system statistics summary email to all ADMIN users"],
    mixinStandardHelpOptions = true
)
class SendAdminSummaryCommand : Runnable {

    @Option(
        names = ["--dry-run"],
        description = ["Preview planned recipients without sending emails"]
    )
    var dryRun: Boolean = false

    @Option(
        names = ["--verbose", "-v"],
        description = ["Detailed logging (show per-recipient status)"]
    )
    var verbose: Boolean = false

    @Spec
    lateinit var spec: Model.CommandSpec

    @Inject
    lateinit var adminSummaryCliService: AdminSummaryCliService

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

            // Gather and display statistics
            val statistics = adminSummaryCliService.getStatistics()
            if (dryRun) {
                println("Statistics to be sent:")
            } else {
                println("Gathering statistics...")
            }
            println("   Users: ${statistics.userCount}")
            println("   Vulnerabilities: ${statistics.vulnerabilityCount}")
            println("   Assets: ${statistics.assetCount}")
            println()

            println("Vulnerability Statistics: ${statistics.vulnerabilityStatisticsUrl}")
            println()

            if (statistics.topProducts.isNotEmpty()) {
                println("Top 10 Most Affected Products:")
                statistics.topProducts.forEach { product ->
                    println("   ${product.name}: ${product.vulnerabilityCount}")
                }
                println()
            }

            if (statistics.topServers.isNotEmpty()) {
                println("Top 10 Most Affected Servers:")
                statistics.topServers.forEach { server ->
                    println("   ${server.name}: ${server.vulnerabilityCount}")
                }
                println()
            }

            // Execute send
            val result = adminSummaryCliService.execute(dryRun, verbose)

            if (dryRun) {
                println("Would send to ${result.recipientCount} ADMIN users:")
                result.recipients.forEach { email ->
                    println("   - $email")
                }
            } else {
                println("Sending to ${result.recipientCount} ADMIN users...")
                if (verbose) {
                    result.recipients.forEach { email ->
                        println("   SUCCESS $email")
                    }
                    result.failedRecipients.forEach { email ->
                        println("   FAILED $email")
                    }
                }
            }

            println()
            println("=" .repeat(60))
            println("Summary")
            println("=" .repeat(60))
            println("Recipients: ${result.recipientCount}")
            println("Emails sent: ${result.emailsSent}")
            println("Failures: ${result.emailsFailed}")
            println()

            when (result.status) {
                ExecutionStatus.SUCCESS -> {
                    println("Admin summary email sent successfully")
                }
                ExecutionStatus.DRY_RUN -> {
                    println("Dry run complete - no emails sent")
                }
                ExecutionStatus.PARTIAL_FAILURE -> {
                    println("Admin summary email completed with some failures")
                    if (verbose && result.failedRecipients.isNotEmpty()) {
                        println()
                        println("Failed recipients:")
                        result.failedRecipients.forEach { println("   - $it") }
                    }
                }
                ExecutionStatus.FAILURE -> {
                    println("Admin summary email failed")
                    if (result.recipientCount == 0) {
                        println("No ADMIN users with valid email found")
                    }
                }
            }

            // Exit with appropriate code
            if (result.status == ExecutionStatus.FAILURE ||
                result.status == ExecutionStatus.PARTIAL_FAILURE) {
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
}
