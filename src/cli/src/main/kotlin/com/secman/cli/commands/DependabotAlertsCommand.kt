package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import jakarta.inject.Inject
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * CLI command to pull GitHub Dependabot alerts (HIGH/CRITICAL by default) and
 * import them into SecMan as repository vulnerabilities.
 *
 * Each repository becomes a REPOSITORY-type asset; its alerts become
 * vulnerabilities tagged source=GITHUB_DEPENDABOT. A re-import reflects
 * remediated alerts by their absence (transactional replace per repository).
 *
 * Usage:
 *   secman dependabot-alerts --org my-org --github-token <PAT> --save
 *   secman dependabot-alerts --repos owner/repo,owner/other --dry-run
 */
@Singleton
@Command(
    name = "dependabot-alerts",
    description = ["Import GitHub Dependabot alerts (high/critical) as repository vulnerabilities"],
    mixinStandardHelpOptions = true
)
class DependabotAlertsCommand : Runnable {

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    @Option(names = ["--org"], description = ["GitHub organization to scan (repeatable). Pulls alerts across all its repos."])
    var orgs: MutableList<String> = mutableListOf()

    @Option(names = ["--repos"], split = ",", description = ["Comma-separated owner/repo list to scan."])
    var repos: MutableList<String> = mutableListOf()

    // NOTE: initialized empty (not [HIGH,CRITICAL]) because picocli APPENDS to a
    // pre-populated collection option rather than replacing it. The default is
    // applied in run() when the user passed nothing.
    @Option(names = ["--severity"], split = ",", description = ["Severities to import (default: HIGH,CRITICAL)."])
    var severities: MutableList<String> = mutableListOf()

    @Option(names = ["--state"], description = ["Dependabot alert state to query (default: open)."])
    var state: String = "open"

    @Option(names = ["--github-token"], description = ["GitHub access token (or set GITHUB_TOKEN env var)."])
    var githubToken: String? = null

    @Option(names = ["--github-api-url"], description = ["GitHub API base URL (default: https://api.github.com)."])
    var githubApiUrl: String = "https://api.github.com"

    @Option(names = ["--save"], description = ["POST the imported alerts to the backend."])
    var save: Boolean = false

    @Option(names = ["--dry-run"], description = ["Fetch and summarize without saving."])
    var dryRun: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Verbose output."])
    var verbose: Boolean = false

    @Option(names = ["--username"], description = ["Backend username (or set SECMAN_ADMIN_NAME env var)."])
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or set SECMAN_ADMIN_PASS env var)."])
    var password: String? = null

    @Option(names = ["--backend-url"], description = ["Backend API URL (or set SECMAN_BACKEND_URL env var)."])
    var backendUrl: String? = null

    private val mapper get() = cliHttpClient.getObjectMapper()

    override fun run() {
        println("=".repeat(60))
        println("GitHub Dependabot Alert Import")
        println("=".repeat(60))
        println()

        if (orgs.isEmpty() && repos.isEmpty()) {
            System.err.println("Error: provide at least one --org or --repos value")
            System.exit(1)
            return
        }

        val token = githubToken ?: System.getenv("GITHUB_TOKEN")
        if (token.isNullOrBlank()) {
            System.err.println("Error: GitHub token required. Use --github-token or set GITHUB_TOKEN")
            System.exit(1)
            return
        }

        val effectiveSeverities = severities.ifEmpty { mutableListOf("HIGH", "CRITICAL") }
        val severitySet = effectiveSeverities.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        val apiBase = githubApiUrl.trimEnd('/')

        try {
            // Group alerts by repository full name → batch map.
            val byRepo = linkedMapOf<String, MutableList<Map<String, Any?>>>()
            val repoUrls = mutableMapOf<String, String?>()

            for (org in orgs) {
                if (verbose) println("Fetching alerts for org '$org'...")
                val alerts = fetchAllAlerts("$apiBase/orgs/${enc(org)}/dependabot/alerts?state=${enc(state)}&per_page=100", token)
                for (alert in alerts) {
                    val repoMap = alert["repository"] as? Map<*, *>
                    val fullName = repoMap?.get("full_name")?.toString() ?: continue
                    byRepo.getOrPut(fullName) { mutableListOf() }.add(alert)
                    repoUrls.putIfAbsent(fullName, repoMap["html_url"]?.toString())
                }
            }

            for (repo in repos) {
                val trimmed = repo.trim()
                val parts = trimmed.split("/")
                if (parts.size != 2 || parts.any { it.isBlank() }) {
                    System.err.println("Skipping malformed --repos entry '$repo' (expected owner/repo)")
                    continue
                }
                if (verbose) println("Fetching alerts for repo '$trimmed'...")
                val alerts = fetchAllAlerts(
                    "$apiBase/repos/${enc(parts[0])}/${enc(parts[1])}/dependabot/alerts?state=${enc(state)}&per_page=100", token
                )
                byRepo.getOrPut(trimmed) { mutableListOf() }.addAll(alerts)
                repoUrls.putIfAbsent(trimmed, "https://github.com/$trimmed")
            }

            // Build backend batches, filtering by severity.
            var totalAlerts = 0
            val batches = byRepo.mapNotNull { (fullName, alerts) ->
                val mapped = alerts.mapNotNull { toAlertDto(it, severitySet) }
                if (mapped.isEmpty()) null
                else {
                    totalAlerts += mapped.size
                    mapOf(
                        "repositoryFullName" to fullName,
                        "repositoryUrl" to repoUrls[fullName],
                        "alerts" to mapped
                    )
                }
            }

            println("Repositories with matching alerts: ${batches.size}")
            println("Total alerts (${severitySet.joinToString(",")}): $totalAlerts")
            println()

            if (verbose || dryRun || !save) {
                batches.forEach { batch ->
                    val alertList = batch["alerts"] as List<*>
                    println("  ${batch["repositoryFullName"]}: ${alertList.size} alerts")
                }
                println()
            }

            if (!save || dryRun) {
                println(if (dryRun) "Dry run complete - nothing saved." else "Not saving (use --save to import).")
                return
            }

            if (batches.isEmpty()) {
                println("No matching alerts to import.")
                return
            }

            val effectiveUrl = getEffectiveBackendUrl()
            val authToken = cliHttpClient.authenticate(getEffectiveUsername(), getEffectivePassword(), effectiveUrl)
                ?: throw RuntimeException("Backend authentication failed. Check credentials.")

            val result = cliHttpClient.postMap("$effectiveUrl/api/github/dependabot/import", batches, authToken)
                ?: throw RuntimeException("Import failed - no response from backend")

            val imported = (result["vulnerabilitiesImported"] as? Number)?.toInt() ?: 0
            val created = (result["reposCreated"] as? Number)?.toInt() ?: 0
            val updated = (result["reposUpdated"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val errors = (result["errors"] as? List<String>) ?: emptyList()

            println("=".repeat(60))
            println("Summary")
            println("=".repeat(60))
            println("Repositories created: $created")
            println("Repositories updated: $updated")
            println("Vulnerabilities imported: $imported")
            println("Errors: ${errors.size}")
            errors.forEach { println("  - $it") }

            if (errors.isNotEmpty()) System.exit(1)
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) e.printStackTrace()
            System.exit(1)
        }
    }

    /**
     * Fetch all pages of a Dependabot alerts endpoint, following the Link header's
     * rel="next" cursor. Returns an empty list on 404 (alerts disabled / no access).
     */
    @Suppress("UNCHECKED_CAST")
    private fun fetchAllAlerts(startUrl: String, token: String): List<Map<String, Any?>> {
        val client = HttpClient.newHttpClient()
        val all = mutableListOf<Map<String, Any?>>()
        var next: String? = startUrl
        while (next != null) {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(next))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            when (resp.statusCode()) {
                in 200..299 -> {
                    val page = mapper.readValue(resp.body(), List::class.java) as List<Map<String, Any?>>
                    all.addAll(page)
                    next = parseNextLink(resp.headers().firstValue("Link").orElse(null))
                }
                404 -> {
                    if (verbose) println("  (404 - Dependabot alerts not available for $next)")
                    return all
                }
                else -> throw RuntimeException("GitHub API ${resp.statusCode()}: ${resp.body().take(300)}")
            }
        }
        return all
    }

    private fun parseNextLink(linkHeader: String?): String? {
        if (linkHeader.isNullOrBlank()) return null
        // e.g. <https://api.github.com/...&after=Y3Vyc29yOnYy>; rel="next", <...>; rel="last"
        return linkHeader.split(",")
            .map { it.trim() }
            .firstOrNull { it.contains("rel=\"next\"") }
            ?.substringAfter("<")?.substringBefore(">")
    }

    /** Map a GitHub alert JSON object to a backend DependabotAlertDto map, or null if filtered out. */
    private fun toAlertDto(alert: Map<String, Any?>, severitySet: Set<String>): Map<String, Any?>? {
        val advisory = alert["security_advisory"] as? Map<*, *> ?: return null
        val severity = advisory["severity"]?.toString()?.lowercase() ?: return null
        if (severitySet.isNotEmpty() && severity !in severitySet) return null

        val ghsaId = advisory["ghsa_id"]?.toString() ?: return null
        val cveId = advisory["cve_id"]?.toString()?.takeIf { it.isNotBlank() }

        val securityVuln = alert["security_vulnerability"] as? Map<*, *>
        val pkg = securityVuln?.get("package") as? Map<*, *>
        val ecosystem = pkg?.get("ecosystem")?.toString()
        val packageName = pkg?.get("name")?.toString()
        val versionRange = securityVuln?.get("vulnerable_version_range")?.toString()
        val createdAt = alert["created_at"]?.toString()

        return mapOf(
            "ghsaId" to ghsaId,
            "cveId" to cveId,
            "severity" to severity.uppercase(),
            "ecosystem" to ecosystem,
            "packageName" to packageName,
            "vulnerableVersionRange" to versionRange,
            "createdAt" to createdAt
        )
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

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
