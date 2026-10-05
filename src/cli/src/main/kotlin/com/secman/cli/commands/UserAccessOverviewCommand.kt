package com.secman.cli.commands

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.cli.service.UserAccessOverviewCliService
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.net.URI
import java.util.concurrent.Callable

@Singleton
@Command(name = "user-access-overview", mixinStandardHelpOptions = true,
    description = ["Explain a user's asset, AWS-account and AD-domain access (ADMIN only)"])
class UserAccessOverviewCommand(
    private val service: UserAccessOverviewCliService,
    private val mapper: ObjectMapper
) : Callable<Int> {
    enum class Format { table, json }

    @Option(names = ["--email"], required = true, description = ["Target user's email address"])
    lateinit var email: String

    @Option(names = ["--format"], description = ["Output: table or json (default: table)"])
    var format: Format = Format.table

    @Option(names = ["--page-size"], description = ["Assets fetched per request, 1..500 (default: 100); all pages are fetched"])
    var pageSize: Int = 100

    @Option(names = ["--backend-url"], description = ["Backend URL; defaults to SECMAN_HOST or SECMAN_BACKEND_URL"])
    var backendUrl: String? = null

    @Spec lateinit var spec: CommandSpec

    override fun call(): Int {
        return try {
            require(pageSize in 1..500) { "--page-size must be between 1 and 500" }
            val raw = backendUrl ?: System.getenv("SECMAN_HOST") ?: System.getenv("SECMAN_BACKEND_URL")
                ?: throw IllegalArgumentException("Set SECMAN_HOST / SECMAN_BACKEND_URL or --backend-url")
            val url = validateBackendUrl(raw)
            val username = credential("SECMAN_ADMIN_NAME")
            val password = credential("SECMAN_ADMIN_PASS")
            val report = service.fetch(url, username, password, email.trim(), pageSize)
            val out = spec.commandLine().out
            if (format == Format.json) out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
            else out.print(renderTable(report))
            out.flush()
            0
        } catch (e: IllegalArgumentException) {
            spec.commandLine().err.println("Error: ${e.message}")
            2
        } catch (e: UserAccessOverviewCliService.ReportFailure) {
            spec.commandLine().err.println("Error: ${e.message}")
            e.exitCode
        } catch (e: Exception) {
            spec.commandLine().err.println("Error: Could not retrieve a complete access report (${e.javaClass.simpleName})")
            4
        }
    }

    private fun credential(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() && !it.startsWith("pass://") }
        ?: throw IllegalArgumentException("$name must be supplied through the secret provider")

    companion object {
        fun validateBackendUrl(raw: String): URI {
            val uri = URI(if (raw.contains("://")) raw.trimEnd('/') else "https://${raw.trimEnd('/')}")
            require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty()) {
                "Backend URL must be an origin without credentials, path, query, or fragment"
            }
            require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))) {
                "Backend URL requires HTTPS; HTTP is allowed only for local loopback development"
            }
            return uri
        }

        private fun text(node: JsonNode): String = node.asText("-").map { if (it.isISOControl()) ' ' else it }.joinToString("")

        fun renderTable(report: JsonNode): String = buildString {
            val user = report.path("user")
            appendLine("User: ${text(user.path("email"))} (${text(user.path("username"))}, ID ${text(user.path("id"))})")
            appendLine("Status: ${if (user.path("enabled").asBoolean()) "enabled" else "disabled"}")
            appendLine("Roles: ${user.path("roles").joinToString(", ") { text(it) }}")
            appendLine("Generated: ${text(report.path("generatedAt"))}")
            appendLine("Vulnerability list access: ${if (report.path("vulnerabilityAccess").asBoolean()) "yes" else "no"}")
            appendLine("Global asset access: ${if (report.path("globalAccess").asBoolean()) "yes" else "no"}")
            appendLine("Visible assets: ${text(report.path("totalAssets"))}")
            appendLine("AWS accounts represented: ${report.path("awsAccounts").count { it.path("visibleAssetCount").asLong() > 0 }}")
            appendLine("AD domains represented: ${report.path("adDomains").count { it.path("visibleAssetCount").asLong() > 0 }}")
            if (!report.path("vulnerabilityAccess").asBoolean()) appendLine("Asset scope is shown separately; the user cannot open the current vulnerability list.")
            for ((key, label) in listOf("awsAccounts" to "AWS ACCOUNTS", "adDomains" to "AD DOMAINS")) {
                appendLine("\n$label")
                appendLine("Value | Display name | Visible assets | Scope | Grant reasons")
                for (entry in report.path(key)) {
                    appendLine("${text(entry.path("value"))} | ${text(entry.path("displayName"))} | ${text(entry.path("visibleAssetCount"))} | " +
                        "${if (entry.path("wholeScopeAccess").asBoolean()) "whole scope" else "visible assets only"} | ${reasons(entry.path("reasons"))}" +
                        if (entry.path("visibleAssetCount").asLong() == 0L) " (no matching assets)" else "")
                }
                if (report.path(key).isEmpty) appendLine("(none)")
            }
            appendLine("\nASSETS")
            appendLine("ID | Name | AWS account | AD domain | Access reasons")
            for (asset in report.path("assets")) {
                appendLine("${text(asset.path("id"))} | ${text(asset.path("name"))} | ${text(asset.path("awsAccountId"))} | " +
                    "${text(asset.path("adDomain"))} | ${reasons(asset.path("reasons"))}")
            }
            if (report.path("assets").isEmpty) appendLine("(none)")
        }

        private fun reasons(node: JsonNode): String = node.joinToString("; ") {
            text(it.path("type")) + if (it.path("sourceName").isTextual) " (${text(it.path("sourceName"))}, ID ${text(it.path("sourceId"))})" else ""
        }.ifEmpty { "-" }
    }
}
