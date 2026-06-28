package com.secman.cli.commands

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.cli.service.CliHttpClient
import io.micronaut.context.ApplicationContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Queries the GitHub REST API for Dependabot alerts and stores them in secman.
 *
 * Usage:
 *   secman query dependabot-alerts --org my-org [--save]
 *   secman query dependabot-alerts --repo owner/name --state open --save
 *
 * Either `--org` (all repositories in an organization) or `--repo owner/name`
 * (a single repository) is required. The GitHub token is read from `--token`
 * or the `GITHUB_TOKEN` environment variable.
 *
 * Without `--save` the alerts are printed (dry run). With `--save` they are
 * POSTed to `POST /api/dependabot-alerts/import`, which upserts by
 * (repository, alert number). Backend auth uses SECMAN_ADMIN_NAME /
 * SECMAN_ADMIN_PASS, exactly like `query servers --save`.
 */
class DependabotAlertsCommand {

    private val log = LoggerFactory.getLogger(DependabotAlertsCommand::class.java)

    var org: String? = null
    var repo: String? = null
    var token: String? = null
    var state: String = "open"
    var severity: String? = null
    var save: Boolean = false
    var dryRun: Boolean = false
    var verbose: Boolean = false
    var backendUrl: String? = null

    private val appContext by lazy {
        val resolvedUrl = backendUrl
            ?: System.getenv("SECMAN_BACKEND_URL")
            ?: System.getenv("SECMAN_HOST")?.let { host ->
                if (host.startsWith("http://") || host.startsWith("https://")) host else "https://$host"
            }
        if (resolvedUrl != null) {
            ApplicationContext.builder()
                .properties(mapOf("secman.backend.base-url" to resolvedUrl))
                .start()
        } else {
            ApplicationContext.run()
        }
    }
    private val cliHttpClient: CliHttpClient by lazy { appContext.getBean(CliHttpClient::class.java) }
    private val mapper: ObjectMapper by lazy { cliHttpClient.getObjectMapper() }

    private val github: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    }

    fun execute(): Int {
        if (org.isNullOrBlank() == repo.isNullOrBlank()) {
            System.err.println("Error: exactly one of --org or --repo <owner/name> is required")
            return 1
        }
        val ghToken = token ?: System.getenv("GITHUB_TOKEN")
        if (ghToken.isNullOrBlank()) {
            System.err.println("Error: GitHub token required (--token or GITHUB_TOKEN env var)")
            return 1
        }

        val alerts = try {
            fetchAllAlerts(ghToken)
        } catch (e: Exception) {
            System.err.println("Error: failed to query GitHub Dependabot alerts: ${e.message}")
            log.error("GitHub query failed", e)
            return 1
        }

        System.out.println("Fetched ${alerts.size} Dependabot alert(s) from GitHub")
        if (verbose || dryRun || !save) {
            alerts.take(50).forEach { a ->
                System.out.println(
                    "  ${a["repository"]}#${a["alertNumber"]}  [${a["severity"]}] " +
                        "${a["packageName"]} (${a["ecosystem"]}) ${a["cveId"] ?: a["ghsaId"] ?: ""} — ${a["state"]}"
                )
            }
            if (alerts.size > 50) System.out.println("  … and ${alerts.size - 50} more")
        }

        if (!save || dryRun) {
            if (dryRun) System.out.println("Dry run — not storing in secman.")
            return 0
        }

        if (alerts.isEmpty()) {
            System.out.println("No alerts to store.")
            return 0
        }

        // --- Store in secman ---
        val resolvedBackendUrl = backendUrl
            ?: System.getenv("SECMAN_BACKEND_URL")
            ?: System.getenv("SECMAN_HOST")?.let { host ->
                if (host.startsWith("http://") || host.startsWith("https://")) host else "https://$host"
            }
            ?: "http://localhost:8080"

        val username = System.getenv("SECMAN_ADMIN_NAME")
        val password = System.getenv("SECMAN_ADMIN_PASS")
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            System.err.println("Error: SECMAN_ADMIN_NAME and SECMAN_ADMIN_PASS are required for --save")
            return 1
        }

        val authToken = cliHttpClient.authenticate(username, password, resolvedBackendUrl)
        if (authToken == null) {
            System.err.println("Error: Failed to authenticate with backend at $resolvedBackendUrl. See details above.")
            return 1
        }

        val (status, body) = cliHttpClient.postMapWithStatus("/api/dependabot-alerts/import", alerts, authToken)
        if (status != 200) {
            System.err.println("Error: import failed (HTTP $status): ${body ?: ""}")
            return 1
        }

        System.out.println(
            "Stored in secman: received=${body?.get("received")}, " +
                "created=${body?.get("created")}, updated=${body?.get("updated")}, " +
                "skipped=${body?.get("skipped")}"
        )
        return 0
    }

    /** Page through the GitHub Dependabot alerts API and map each alert to the secman import shape. */
    private fun fetchAllAlerts(ghToken: String): List<Map<String, Any?>> {
        val basePath = if (!org.isNullOrBlank()) {
            "/orgs/$org/dependabot/alerts"
        } else {
            "/repos/$repo/dependabot/alerts"
        }
        val result = mutableListOf<Map<String, Any?>>()
        var page = 1
        while (true) {
            val params = buildString {
                append("?per_page=100&page=").append(page)
                append("&state=").append(state)
                if (!severity.isNullOrBlank()) append("&severity=").append(severity)
            }
            val uri = URI.create("https://api.github.com$basePath$params")
            if (verbose) log.info("GET {}", uri)

            val request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer $ghToken")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build()

            val response = github.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 404) {
                throw RuntimeException("GitHub returned 404 for $basePath — check the org/repo name and that the token has 'security_events' (or repo) scope.")
            }
            if (response.statusCode() !in 200..299) {
                throw RuntimeException("GitHub HTTP ${response.statusCode()}: ${response.body().take(300)}")
            }

            val pageAlerts = mapper.readTree(response.body())
            if (!pageAlerts.isArray || pageAlerts.isEmpty) break

            pageAlerts.forEach { node -> result.add(mapAlert(node)) }

            if (pageAlerts.size() < 100) break
            page++
        }
        return result
    }

    /** Map one GitHub alert JSON node to the secman ImportRequest field shape. */
    private fun mapAlert(node: JsonNode): Map<String, Any?> {
        val dependency = node.path("dependency")
        val pkg = dependency.path("package")
        val advisory = node.path("security_advisory")
        val vuln = node.path("security_vulnerability")
        // Org-level responses carry repository.full_name; repo-level ones don't, so fall back to --repo.
        val repoFullName = txt(node.path("repository").path("full_name")) ?: repo ?: ""

        return mapOf(
            "repository" to repoFullName,
            "alertNumber" to node.path("number").asInt(),
            "state" to txt(node.path("state")),
            "packageName" to txt(pkg.path("name")),
            "ecosystem" to txt(pkg.path("ecosystem")),
            "manifestPath" to txt(dependency.path("manifest_path")),
            "severity" to (txt(advisory.path("severity")) ?: txt(vuln.path("severity"))),
            "ghsaId" to txt(advisory.path("ghsa_id")),
            "cveId" to txt(advisory.path("cve_id")),
            "summary" to txt(advisory.path("summary")),
            "vulnerableVersionRange" to txt(vuln.path("vulnerable_version_range")),
            "firstPatchedVersion" to txt(vuln.path("first_patched_version").path("identifier")),
            "htmlUrl" to txt(node.path("html_url")),
            "alertCreatedAt" to txt(node.path("created_at")),
            "alertUpdatedAt" to txt(node.path("updated_at")),
            "dismissedAt" to txt(node.path("dismissed_at")),
            "fixedAt" to txt(node.path("fixed_at"))
        )
    }

    /** Text value of a node, or null when the node is missing or JSON null (avoids the "null" string). */
    private fun txt(n: JsonNode): String? = if (n.isMissingNode || n.isNull) null else n.asText()
}
