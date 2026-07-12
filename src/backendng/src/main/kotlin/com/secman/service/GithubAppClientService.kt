package com.secman.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.GithubAppConfig
import com.secman.util.PemUtils
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Talks to the GitHub REST API as a GitHub App: signs the RS256 App JWT
 * (plain JDK crypto, same approach as [JwksValidationService] on the verify
 * side), exchanges it for an installation token, lists the installation's
 * repositories and counts their open Dependabot alerts.
 *
 * Required App permissions: Metadata (read) and Dependabot alerts (read).
 */
@Singleton
open class GithubAppClientService(
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(GithubAppClientService::class.java)

    private val httpClient: JdkHttpClient = JdkHttpClient.newBuilder()
        .version(JdkHttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /** Cached installation token: (configId, token, expiry). */
    @Volatile
    private var cachedToken: Triple<Long, String, Instant>? = null

    open val apiBaseUrl: String = "https://api.github.com"

    class GithubApiException(message: String, val status: Int = 0, cause: Throwable? = null) :
        RuntimeException(message, cause)

    @Serdeable
    data class GithubRepoDto(
        val repoId: Long,
        val name: String,
        val owner: String,
        val fullName: String,
        val htmlUrl: String?,
        val archived: Boolean
    )

    @Serdeable
    data class AlertDto(
        val alertNumber: Int,
        val packageName: String,
        val ecosystem: String,
        val manifestPath: String?,
        val severity: String,
        val ghsaId: String?,
        val cveId: String?,
        val summary: String?,
        val vulnerableVersionRange: String?,
        val firstPatchedVersion: String?,
        val htmlUrl: String?,
        val alertCreatedAt: Instant?,
        val alertUpdatedAt: Instant?
    )

    @Serdeable
    data class SeverityCounts(
        val critical: Int = 0,
        val high: Int = 0,
        /** True when Dependabot alerts are disabled/inaccessible for the repo. */
        val disabled: Boolean = false,
        val alerts: List<AlertDto> = emptyList()
    )

    // ------------------------------------------------------------------
    // App JWT + installation token
    // ------------------------------------------------------------------

    /** RS256-signed GitHub App JWT (iat 60s in the past, exp +9min per GitHub docs). */
    fun buildAppJwt(config: GithubAppConfig): String {
        val now = Instant.now().epochSecond
        val header = base64Url("""{"alg":"RS256","typ":"JWT"}""".toByteArray(StandardCharsets.UTF_8))
        val payload = base64Url(
            """{"iat":${now - 60},"exp":${now + 540},"iss":"${config.appId}"}"""
                .toByteArray(StandardCharsets.UTF_8)
        )
        val signingInput = "$header.$payload"
        val signature = try {
            val key = PemUtils.parseRsaPrivateKey(config.privateKeyPem)
            Signature.getInstance("SHA256withRSA").run {
                initSign(key)
                update(signingInput.toByteArray(StandardCharsets.UTF_8))
                sign()
            }
        } catch (e: IllegalArgumentException) {
            throw GithubApiException("GitHub App private key is invalid: ${e.message}", cause = e)
        }
        return "$signingInput.${base64Url(signature)}"
    }

    /** Installation token for the configured installation (cached until ~5 min before expiry). */
    open fun getInstallationToken(config: GithubAppConfig): String {
        val configId = config.id ?: -1
        cachedToken?.let { (id, token, expiry) ->
            if (id == configId && expiry.isAfter(Instant.now().plusSeconds(300))) {
                return token
            }
        }

        val jwt = buildAppJwt(config)
        val installationId = config.installationId?.takeIf { it.isNotBlank() }
            ?: resolveInstallationId(jwt, config.organization)

        val response = send(
            request("POST", "/app/installations/$installationId/access_tokens", jwt)
        )
        if (response.status !in 200..299) {
            throw GithubApiException(
                "Failed to create installation token (HTTP ${response.status}): ${truncate(response.body)}",
                response.status
            )
        }
        val node = objectMapper.readTree(response.body)
        val token = node.path("token").asText("")
        if (token.isBlank()) {
            throw GithubApiException("Installation token response contained no token")
        }
        val expiresAt = runCatching { Instant.parse(node.path("expires_at").asText()) }
            .getOrDefault(Instant.now().plusSeconds(3600))
        cachedToken = Triple(configId, token, expiresAt)
        return token
    }

    /** Lists App installations and picks the one matching [organization] (or the single one). */
    internal fun resolveInstallationId(appJwt: String, organization: String?): String {
        val installations = mutableListOf<JsonNode>()
        var page = 1
        while (true) {
            val response = send(request("GET", "/app/installations?per_page=100&page=$page", appJwt))
            if (response.status !in 200..299) {
                throw GithubApiException(
                    "Failed to list App installations (HTTP ${response.status}): ${truncate(response.body)}",
                    response.status
                )
            }
            val batch = objectMapper.readTree(response.body)
            if (!batch.isArray || batch.isEmpty) break
            batch.forEach { installations.add(it) }
            if (batch.size() < 100) break
            page++
        }

        if (installations.isEmpty()) {
            throw GithubApiException("The GitHub App has no installations")
        }
        val match = if (!organization.isNullOrBlank()) {
            installations.firstOrNull {
                it.path("account").path("login").asText().equals(organization, ignoreCase = true)
            } ?: throw GithubApiException(
                "No installation found for organization '$organization' " +
                    "(installed on: ${installations.joinToString { it.path("account").path("login").asText() }})"
            )
        } else {
            if (installations.size > 1) {
                throw GithubApiException(
                    "The App has ${installations.size} installations — set the installation ID or " +
                        "organization in the GitHub App configuration to disambiguate"
                )
            }
            installations.first()
        }
        return match.path("id").asLong().toString()
    }

    // ------------------------------------------------------------------
    // Repositories + Dependabot alert counts
    // ------------------------------------------------------------------

    open fun listInstallationRepositories(token: String): List<GithubRepoDto> {
        val repos = mutableListOf<GithubRepoDto>()
        var page = 1
        while (true) {
            val response = send(request("GET", "/installation/repositories?per_page=100&page=$page", token))
            if (response.status !in 200..299) {
                throw GithubApiException(
                    "Failed to list installation repositories (HTTP ${response.status}): ${truncate(response.body)}",
                    response.status
                )
            }
            val batch = objectMapper.readTree(response.body).path("repositories")
            if (!batch.isArray || batch.isEmpty) break
            batch.forEach { repo ->
                repos.add(
                    GithubRepoDto(
                        repoId = repo.path("id").asLong(),
                        name = repo.path("name").asText(""),
                        owner = repo.path("owner").path("login").asText(""),
                        fullName = repo.path("full_name").asText(""),
                        htmlUrl = repo.path("html_url").asText(null),
                        archived = repo.path("archived").asBoolean(false)
                    )
                )
            }
            if (batch.size() < 100) break
            page++
        }
        return repos
    }

    /**
     * Fetches the repo's open Dependabot alerts: counts by severity plus
     * full per-alert detail (package, CVE/GHSA, vulnerable range, etc.) —
     * one pass over the same paginated response. 403/404 (alerts disabled
     * or inaccessible) yields `disabled = true` instead of failing the
     * whole import run.
     *
     * Unlike the other list endpoints, GitHub rejects `page` on this one
     * ("Pagination using the `page` parameter is not supported") — it only
     * supports cursor pagination via the `Link: rel="next"` response header.
     */
    open fun countOpenDependabotAlerts(token: String, owner: String, repo: String): SeverityCounts {
        var critical = 0
        var high = 0
        val alerts = mutableListOf<AlertDto>()
        var url: String? = "$apiBaseUrl/repos/$owner/$repo/dependabot/alerts?state=open&per_page=100"
        while (url != null) {
            val response = send(requestUrl("GET", url, token), retryOnRateLimit = true)
            when {
                response.status == 403 || response.status == 404 -> {
                    log.debug("Dependabot alerts unavailable for {}/{} (HTTP {})", owner, repo, response.status)
                    return SeverityCounts(disabled = true)
                }
                response.status !in 200..299 -> throw GithubApiException(
                    "Failed to list Dependabot alerts for $owner/$repo (HTTP ${response.status}): ${truncate(response.body)}",
                    response.status
                )
            }
            val batch = objectMapper.readTree(response.body)
            if (!batch.isArray || batch.isEmpty) break
            batch.forEach { alert ->
                val severity = alert.path("security_advisory").path("severity").asText("").lowercase()
                when (severity) {
                    "critical" -> critical++
                    "high" -> high++
                }
                alerts.add(parseAlert(alert, severity))
            }
            url = response.nextLink
        }
        return SeverityCounts(critical = critical, high = high, alerts = alerts)
    }

    /** Maps one GitHub Dependabot alert JSON node to [AlertDto]. Package-internal so it's unit-testable without HTTP mocking. */
    internal fun parseAlert(node: JsonNode, severity: String): AlertDto {
        val dependency = node.path("dependency")
        val pkg = dependency.path("package")
        val advisory = node.path("security_advisory")
        val vuln = node.path("security_vulnerability")
        return AlertDto(
            alertNumber = node.path("number").asInt(),
            packageName = text(pkg.path("name")) ?: "",
            ecosystem = text(pkg.path("ecosystem")) ?: "",
            manifestPath = text(dependency.path("manifest_path")),
            severity = severity,
            ghsaId = text(advisory.path("ghsa_id")),
            cveId = text(advisory.path("cve_id")),
            summary = text(advisory.path("summary")),
            vulnerableVersionRange = text(vuln.path("vulnerable_version_range")),
            firstPatchedVersion = text(vuln.path("first_patched_version").path("identifier")),
            htmlUrl = text(node.path("html_url")),
            alertCreatedAt = parseInstant(node.path("created_at")),
            alertUpdatedAt = parseInstant(node.path("updated_at"))
        )
    }

    /** Text value of a node, or null when missing/JSON null (avoids the literal "null" string). */
    private fun text(n: JsonNode): String? = if (n.isMissingNode || n.isNull) null else n.asText()

    /** Parses an ISO-8601 instant node, or null when missing/blank/unparsable. */
    private fun parseInstant(n: JsonNode): Instant? {
        val value = text(n) ?: return null
        return try { Instant.parse(value) } catch (e: Exception) { null }
    }

    /** Credential check for the admin UI: builds a JWT and lists installations. */
    open fun testConnection(config: GithubAppConfig): String {
        val jwt = buildAppJwt(config)
        val installationId = config.installationId?.takeIf { it.isNotBlank() }
            ?: resolveInstallationId(jwt, config.organization)
        return "OK — App ${config.appId}, installation $installationId"
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    private fun request(method: String, path: String, bearer: String): JdkHttpRequest =
        requestUrl(method, "$apiBaseUrl$path", bearer)

    private fun requestUrl(method: String, url: String, bearer: String): JdkHttpRequest {
        return JdkHttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer $bearer")
            .method(method, JdkHttpRequest.BodyPublishers.noBody())
            .build()
    }

    private data class GithubResponse(val status: Int, val body: String, val nextLink: String?)

    /** One bounded retry on secondary rate limits. [GithubResponse.nextLink] is the `Link: rel="next"` URL, if any. */
    private fun send(request: JdkHttpRequest, retryOnRateLimit: Boolean = false): GithubResponse {
        val response = try {
            httpClient.send(request, BodyHandlers.ofString())
        } catch (e: Exception) {
            throw GithubApiException("GitHub API request failed: ${e.message}", cause = e)
        }
        if (retryOnRateLimit && response.statusCode() == 403 &&
            response.headers().firstValue("retry-after").isPresent
        ) {
            val waitSeconds = response.headers().firstValue("retry-after").get().toLongOrNull() ?: 1
            log.warn("GitHub secondary rate limit hit, retrying once after {}s", waitSeconds)
            Thread.sleep(waitSeconds.coerceAtMost(30) * 1000)
            val retried = try {
                httpClient.send(request, BodyHandlers.ofString())
            } catch (e: Exception) {
                throw GithubApiException("GitHub API retry failed: ${e.message}", cause = e)
            }
            return GithubResponse(retried.statusCode(), retried.body(), nextLinkOf(retried))
        }
        return GithubResponse(response.statusCode(), response.body(), nextLinkOf(response))
    }

    /** Parses the `Link` response header's `rel="next"` URL (RFC 8288), e.g. `<url>; rel="next", <url>; rel="prev"`. */
    private fun nextLinkOf(response: java.net.http.HttpResponse<String>): String? {
        val link = response.headers().firstValue("Link").orElse(null) ?: return null
        return link.split(",").firstNotNullOfOrNull { part ->
            val segments = part.split(";").map { it.trim() }
            val url = segments.firstOrNull()?.removePrefix("<")?.removeSuffix(">")
            val isNext = segments.drop(1).any { it.replace(" ", "") == "rel=\"next\"" }
            url?.takeIf { isNext }
        }
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun truncate(body: String): String = body.take(300)
}
