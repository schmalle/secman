package com.secman.cli.commands

import com.fasterxml.jackson.databind.JsonNode
import com.secman.cli.commands.ShowNotifiedUsersCommand.Companion.connection
import com.secman.cli.commands.ShowNotifiedUsersCommand.Companion.runOverdueCommand
import com.secman.cli.service.OverdueNotificationCliService
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

/**
 * One deduplicated overdue-vulnerability email per workgroup member (direct asset assignment
 * or AWS-account workgroup grant). Separate from `send-notification-users`.
 * Exit: 0 success/dry run, 1 any delivery failure, 2 bad input or unknown --notification-user,
 * 3 authentication, 4 backend failure.
 */
@Singleton
@Command(name = "notify-users-overdue", mixinStandardHelpOptions = true, description = ["Email workgroup members about overdue vulnerabilities on their assigned assets and AWS-account assets (ADMIN)"])
class NotifyUsersOverdueCommand(private val service: OverdueNotificationCliService) : Callable<Int> {

    @Option(names = ["--dry-run"], description = ["Resolve recipients without sending emails"])
    var dryRun: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["List every recipient with outcome, asset count and workgroups"])
    var verbose: Boolean = false

    @Option(names = ["--days"], description = ["Vulnerability age threshold in days, 1..3650 (default: 30)"])
    var days: Int = 30

    @Option(names = ["--notification-user"], description = ["Only process this user's email (skip all others)"])
    var notificationUser: String? = null

    @Option(names = ["--backend-url"], description = ["Backend URL; defaults to SECMAN_HOST or SECMAN_BACKEND_URL"])
    var backendUrl: String? = null

    @Spec lateinit var spec: CommandSpec

    override fun call(): Int = runOverdueCommand(spec) {
        require(days in 1..3650) { "--days must be between 1 and 3650" }
        val (url, username, password) = connection(backendUrl)
        val result = service.send(url, username, password, days, dryRun, notificationUser?.trim()?.takeIf { it.isNotEmpty() })
        val out = spec.commandLine().out
        if (result.path("notificationUserExists").isBoolean && !result.path("notificationUserExists").asBoolean()) {
            spec.commandLine().err.println("Error: no user account exists with email '${clean(result.path("notificationUser"))}'. Nothing was sent.")
            return@runOverdueCommand 2
        }
        out.print(renderSummary(result, verbose))
        out.flush()
        exitCode(result)
    }

    companion object {
        private fun clean(node: JsonNode): String = node.asText("-").filterNot { it.isISOControl() }

        /** Any delivery failure is a non-zero exit so cron and automation notice it. */
        fun exitCode(result: JsonNode): Int = when (result.path("status").asText()) {
            "SUCCESS", "DRY_RUN" -> 0
            else -> 1
        }

        /** Per-recipient table for dry runs and --verbose, then the totals; failures are always listed. */
        fun renderSummary(result: JsonNode, verbose: Boolean): String = buildString {
            val dryRun = result.path("dryRun").asBoolean()
            if (dryRun) appendLine("DRY-RUN: no emails were sent")
            if (verbose || dryRun) {
                appendLine("Email | Outcome | Assets | Overdue | Workgroups")
                for (r in result.path("recipients")) {
                    appendLine("${clean(r.path("email"))} | ${clean(r.path("outcome"))} | ${clean(r.path("assetCount"))} | " +
                        "${clean(r.path("overdueCount"))} | ${r.path("workgroups").joinToString(", ") { clean(it) }}")
                }
                if (result.path("recipients").isEmpty) appendLine("(no recipients)")
                appendLine()
            }
            appendLine("Threshold:            ${clean(result.path("thresholdDays"))} days")
            appendLine("Recipients resolved:  ${clean(result.path("recipientsResolved"))}")
            appendLine("Emails sent:          ${clean(result.path("emailsSent"))}")
            appendLine("Emails failed:        ${clean(result.path("emailsFailed"))}")
            appendLine("Skipped (already notified today): ${clean(result.path("skipped"))}")
            appendLine("Status:               ${clean(result.path("status"))}")
            val failed = result.path("recipients").filter { it.path("outcome").asText() == "FAILED" }
            if (failed.isNotEmpty() && !verbose) {
                appendLine("Failed recipients:")
                failed.forEach { appendLine("   - ${clean(it.path("email"))}") }
            }
        }
    }
}
