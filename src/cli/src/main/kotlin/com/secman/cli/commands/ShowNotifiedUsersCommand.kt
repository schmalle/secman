package com.secman.cli.commands

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.cli.service.OverdueNotificationCliService
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.net.URI
import java.util.concurrent.Callable

/**
 * Read-only: which overdue assets `notify-users-overdue` would mail to one user, and why.
 * Exit 0 for every answer about the user (including "unknown user"); non-zero only for bad
 * input (2), authentication (3) or backend failure (4).
 */
@Singleton
@Command(name = "show-notified-users", mixinStandardHelpOptions = true, description = ["Show the overdue assets notify-users-overdue would mail to one user, with the reason for each (ADMIN)"])
class ShowNotifiedUsersCommand(
    private val service: OverdueNotificationCliService,
    private val mapper: ObjectMapper
) : Callable<Int> {
    /** `json` prints the backend report unchanged for scripting. */
    enum class Format { table, json }

    @Option(names = ["--email"], required = true, description = ["The user's login email address"])
    lateinit var email: String

    @Option(names = ["--days"], description = ["Vulnerability age threshold in days, 1..3650 (default: 30)"])
    var days: Int = 30

    @Option(names = ["--format"], description = ["Output: table or json (default: table)"])
    var format: Format = Format.table

    @Option(names = ["--backend-url"], description = ["Backend URL; defaults to SECMAN_HOST or SECMAN_BACKEND_URL"])
    var backendUrl: String? = null

    @Spec lateinit var spec: CommandSpec

    override fun call(): Int = runOverdueCommand(spec) {
        require(days in 1..3650) { "--days must be between 1 and 3650" }
        val (url, username, password) = connection(backendUrl)
        val report = service.overview(url, username, password, email.trim(), days)
        val out = spec.commandLine().out
        if (format == Format.json) out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        else out.print(renderOverview(report))
        out.flush()
        0
    }

    companion object {
        private fun text(node: JsonNode): String =
            node.asText("-").ifEmpty { "-" }.map { if (it.isISOControl()) ' ' else it }.joinToString("")

        fun reason(node: JsonNode): String =
            if (node.path("type").asText() == "AWS_WORKGROUP")
                "AWS_WORKGROUP:${text(node.path("workgroupName"))}/${text(node.path("awsAccountId"))}"
            else "DIRECT_ASSIGNMENT:${text(node.path("workgroupName"))}"

        /** One distinct sentence per status, so "nothing would be sent" always says why. */
        fun statusLine(report: JsonNode): String = when (report.path("status").asText()) {
            "USER_NOT_FOUND" -> "No user account exists with this email. Nothing would be sent."
            "USER_DISABLED" -> "The user account is disabled. Disabled users are never notified."
            "NO_PATHS" -> "The user is not a direct member of any enabled workgroup whose name starts with lowercase aws- with assigned assets or AWS accounts. Nothing would be sent."
            "NO_OVERDUE_ASSETS" -> "The user has notification paths, but none of their current assets has overdue findings. Nothing would be sent."
            "WOULD_BE_NOTIFIED" -> "The user would receive one email listing the assets below."
            else -> "Unknown status ${text(report.path("status"))}"
        }

        /** Table output; control characters in backend values are blanked so names cannot drive the terminal. */
        fun renderOverview(report: JsonNode): String = buildString {
            appendLine("User: ${text(report.path("email"))}" +
                if (report.hasNonNull("userId")) " (${text(report.path("username"))}, ID ${text(report.path("userId"))})" else "")
            appendLine("Status: ${text(report.path("status"))}")
            appendLine(statusLine(report))
            appendLine("Threshold: ${text(report.path("thresholdDays"))} days")
            appendLine("Observation window (UTC): ${text(report.path("observationWindowStartUtc"))} .. ${text(report.path("observationWindowEndUtc"))}")
            if (report.path("alreadyNotifiedToday").asBoolean()) {
                appendLine("Already notified today: yes (notify-users-overdue would skip this user until the next UTC day)")
            }
            if (report.path("status").asText() == "USER_NOT_FOUND") return@buildString

            appendLine("\nWORKGROUPS (direct membership)")
            appendLine("Only enabled workgroups whose names start with lowercase aws- contribute to overdue asset totals; other groups are ignored.")
            appendLine("Name | ID | Enabled | Assigned assets | AWS accounts")
            for (group in report.path("workgroups")) {
                appendLine("${text(group.path("name"))} | ${text(group.path("workgroupId"))} | " +
                    "${if (group.path("enabled").asBoolean()) "yes" else "no (grants nothing)"} | " +
                    "${text(group.path("assignedAssetCount"))} | ${group.path("awsAccountIds").joinToString(", ") { text(it) }.ifEmpty { "-" }}")
            }
            if (report.path("workgroups").isEmpty) appendLine("(none)")

            appendLine("\nOVERDUE ASSETS")
            appendLine("ID | Name | AWS account | Overdue | Critical | High | Medium | Low | Oldest | Reasons")
            for (asset in report.path("assets")) {
                appendLine("${text(asset.path("assetId"))} | ${text(asset.path("assetName"))} | ${text(asset.path("awsAccountId"))} | " +
                    "${text(asset.path("overdueCount"))} | ${text(asset.path("criticalCount"))} | ${text(asset.path("highCount"))} | " +
                    "${text(asset.path("mediumCount"))} | ${text(asset.path("lowCount"))} | ${text(asset.path("oldestDays"))}d | " +
                    asset.path("reasons").joinToString("; ") { reason(it) })
            }
            if (report.path("assets").isEmpty) appendLine("(none)")
            appendLine("\nTotals: ${text(report.path("totalAssets"))} assets, ${text(report.path("totalOverdue"))} overdue findings " +
                "(${text(report.path("totalCritical"))} critical, ${text(report.path("totalHigh"))} high)")
        }

        /** Backend origin and ADMIN credentials, shared by both overdue commands. */
        internal data class Connection(val url: URI, val username: String, val password: String)

        /** Credentials only from the secret provider's environment, never from flags (they would land in shell history). */
        internal fun connection(backendUrl: String?): Connection {
            val raw = backendUrl ?: System.getenv("SECMAN_HOST") ?: System.getenv("SECMAN_BACKEND_URL")
                ?: throw IllegalArgumentException("Set SECMAN_HOST / SECMAN_BACKEND_URL or --backend-url")
            return Connection(UserAccessOverviewCommand.validateBackendUrl(raw), credential("SECMAN_ADMIN_NAME"), credential("SECMAN_ADMIN_PASS"))
        }

        private fun credential(name: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() && !it.startsWith("pass://") }
                ?: throw IllegalArgumentException("$name must be supplied through the secret provider")

        /** Shared exit-code mapping for both overdue commands. */
        internal fun runOverdueCommand(spec: CommandSpec, block: () -> Int): Int = try {
            block()
        } catch (e: IllegalArgumentException) {
            spec.commandLine().err.println("Error: ${e.message}")
            2
        } catch (e: OverdueNotificationCliService.Failure) {
            spec.commandLine().err.println("Error: ${e.message}")
            e.exitCode
        } catch (e: Exception) {
            spec.commandLine().err.println("Error: request failed (${e.javaClass.simpleName})")
            4
        }
    }
}
