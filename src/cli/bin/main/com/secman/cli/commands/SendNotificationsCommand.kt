package com.secman.cli.commands

import com.secman.cli.service.NotificationCliService
import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * CLI command to send email notifications for outdated assets and new vulnerabilities
 * Feature 035: Outdated Asset Notification System
 *
 * Usage:
 *   ./gradlew cli:run --args='send-notifications'
 *   ./gradlew cli:run --args='send-notifications --dry-run'
 *   ./gradlew cli:run --args='send-notifications --verbose'
 */
@Singleton
@Command(
    name = "send-notifications",
    description = ["Send email notifications for outdated assets and new vulnerabilities"],
    mixinStandardHelpOptions = true
)
class SendNotificationsCommand : Runnable {

    @Option(
        names = ["--dry-run"],
        description = ["Report planned notifications without sending emails"]
    )
    var dryRun: Boolean = false

    @Option(
        names = ["--verbose", "-v"],
        description = ["Detailed logging (show per-asset processing)"]
    )
    var verbose: Boolean = false

    @Option(
        names = ["--outdated-only"],
        description = ["Process only outdated asset reminders (skip new vulnerability notifications)"]
    )
    var outdatedOnly: Boolean = false

    @Spec
    lateinit var spec: Model.CommandSpec

    lateinit var notificationCliService: NotificationCliService

    override fun run() {
        try {
            println("=" .repeat(60))
            println("SecMan Notification System")
            println("=" .repeat(60))
            println()

            if (dryRun) {
                println("⚠️  DRY-RUN MODE: No emails will be sent")
                println()
            }

            if (verbose) {
                println("Verbose logging enabled")
                println()
            }

            // Process outdated asset notifications
            println("📧 Processing outdated asset notifications...")
            val result = notificationCliService.processOutdatedNotifications(dryRun, verbose)

            println()
            println("=" .repeat(60))
            println("Summary")
            println("=" .repeat(60))
            println("Assets processed: ${result.assetsProcessed}")
            println("Emails sent: ${result.emailsSent}")
            println("Failures: ${result.failures}")
            println("Skipped: ${result.skipped.size}")

            if (verbose && result.skipped.isNotEmpty()) {
                println()
                println("Skipped recipients:")
                result.skipped.forEach { println("  - $it") }
            }

            println()
            println("✅ Notification processing complete")

            // Exit with appropriate code
            if (result.failures > 0) {
                System.exit(1)
            }

        } catch (e: Exception) {
            System.err.println("❌ Error: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            System.exit(1)
        }
    }
}
