package com.secman.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Fetches the end-of-life catalogue from the configured upstream source.
 *
 * Default source is **endoflife.date** — a community-maintained, public,
 * unauthenticated JSON API covering ~350 products (Windows/Windows Server, RHEL,
 * Ubuntu, Debian, SLES, Amazon Linux, ESXi, Java, .NET, Python, Node.js, nginx,
 * Apache, Tomcat, PostgreSQL, MySQL/MariaDB, Spring Boot, Kubernetes, ...). It
 * needs no credential, which is why the fetch lives in the backend rather than
 * behind an operator secret.
 *
 * ### Security (CLAUDE.md §A10)
 * The base URL is operator configuration, never request-derived, but it is still
 * validated on every call because a mis-set `secman.eol.base-url` would
 * otherwise turn this into an SSRF primitive against the backend's own network:
 *  - `https` only, default port only, no userinfo;
 *  - host must be in `secman.eol.allowed-hosts`;
 *  - every resolved address is rejected if loopback, link-local, site-local,
 *    any-local, multicast, or the cloud metadata address;
 *  - redirects are **never** followed (a 30x to an internal host would bypass
 *    all of the above).
 * Response bodies are size-capped and parsed into explicit shapes — never into a
 * polymorphic type (§A08).
 */
@Singleton
open class EolCatalogClient(
    private val objectMapper: ObjectMapper,

    @Value("\${secman.eol.base-url:https://endoflife.date}")
    private val baseUrl: String,

    @Value("\${secman.eol.allowed-hosts:endoflife.date}")
    private val allowedHosts: String,

    @Value("\${secman.eol.timeout-seconds:20}")
    private val timeoutSeconds: Long,

    @Value("\${secman.eol.max-response-bytes:8388608}")
    private val maxResponseBytes: Long
) {
    private val log = LoggerFactory.getLogger(EolCatalogClient::class.java)

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build()
    }

    /** One product's lifecycle data, already normalized away from the wire shape. */
    data class ProductDetail(
        val productKey: String,
        val label: String,
        val category: String?,
        val aliases: Set<String>,
        val uri: String?,
        val releases: List<ReleaseDetail>
    )

    data class ReleaseDetail(
        val cycle: String,
        val label: String?,
        val releaseDate: LocalDate?,
        val eolDate: LocalDate?,
        val supportEndDate: LocalDate?,
        val alreadyEol: Boolean,
        val eolUnknown: Boolean,
        val lts: Boolean,
        val latestVersion: String?
    )

    class EolSourceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    // ------------------------------------------------------------------ fetches

    /**
     * List every product key the source publishes.
     *
     * Tries the v1 index first and falls back to the long-stable legacy
     * `/api/all.json`, so a v1 schema change degrades to the older endpoint
     * instead of taking the whole feature down.
     */
    open fun listProductKeys(): List<String> {
        val v1 = runCatching { parseProductIndexV1(getJson("/api/v1/products")) }.getOrNull()
        if (!v1.isNullOrEmpty()) return v1

        val legacy = runCatching { parseProductIndexLegacy(getJson("/api/all.json")) }.getOrNull()
        if (!legacy.isNullOrEmpty()) return legacy

        throw EolSourceException("EOL source returned no product index")
    }

    /** Fetch one product's cycles. Returns null when the source has no such product. */
    open fun fetchProduct(productKey: String): ProductDetail? {
        val key = sanitizeProductKey(productKey)
        val v1 = runCatching { parseProductV1(key, getJson("/api/v1/products/$key")) }.getOrNull()
        if (v1 != null && v1.releases.isNotEmpty()) return v1

        return runCatching { parseProductLegacy(key, getJson("/api/$key.json")) }.getOrNull()
    }

    // ------------------------------------------------------------------ parsing

    private fun parseProductIndexV1(root: JsonNode): List<String> {
        val result = root.path("result")
        if (!result.isArray) return emptyList()
        return result.mapNotNull { node ->
            val name = node.path("name").asText(null) ?: return@mapNotNull null
            runCatching { sanitizeProductKey(name) }.getOrNull()
        }
    }

    private fun parseProductIndexLegacy(root: JsonNode): List<String> {
        if (!root.isArray) return emptyList()
        return root.mapNotNull { node ->
            if (!node.isTextual) null else runCatching { sanitizeProductKey(node.asText()) }.getOrNull()
        }
    }

    /** endoflife.date API v1: `{"result": {"name", "label", "releases": [...]}}`. */
    private fun parseProductV1(productKey: String, root: JsonNode): ProductDetail? {
        val result = if (root.has("result")) root.path("result") else root
        if (!result.isObject) return null
        val releasesNode = result.path("releases")
        if (!releasesNode.isArray) return null

        val releases = releasesNode.mapNotNull { node ->
            val cycle = firstText(node, "name", "cycle") ?: return@mapNotNull null
            val eolFrom = parseDate(node.path("eolFrom"))
            val isEol = node.path("isEol").let { if (it.isBoolean) it.asBoolean() else false }
            ReleaseDetail(
                cycle = cycle.take(100),
                label = firstText(node, "label")?.take(255),
                releaseDate = parseDate(node.path("releaseDate")),
                eolDate = eolFrom,
                supportEndDate = parseDate(node.path("eoasFrom")),
                alreadyEol = isEol || (eolFrom != null && !eolFrom.isAfter(LocalDate.now())),
                eolUnknown = eolFrom == null && !node.path("isEol").isBoolean,
                lts = node.path("isLts").let { if (it.isBoolean) it.asBoolean() else false },
                latestVersion = firstText(node.path("latest"), "name")?.take(100)
                    ?: firstText(node, "latest")?.take(100)
            )
        }
        if (releases.isEmpty()) return null

        val aliases = result.path("aliases").let { node ->
            if (node.isArray) node.mapNotNull { it.asText(null) }.toSet() else emptySet()
        }
        return ProductDetail(
            productKey = productKey,
            label = (firstText(result, "label", "name") ?: productKey).take(255),
            category = firstText(result, "category")?.take(64),
            aliases = aliases,
            uri = firstText(result, "uri")?.take(1024) ?: "$baseUrl/$productKey",
            releases = releases
        )
    }

    /** Legacy shape: a bare array of cycles, `eol` either a date or a boolean. */
    private fun parseProductLegacy(productKey: String, root: JsonNode): ProductDetail? {
        if (!root.isArray) return null
        val releases = root.mapNotNull { node ->
            val cycle = firstText(node, "cycle", "name") ?: return@mapNotNull null
            val eolNode = node.path("eol")
            val eolDate = parseDate(eolNode)
            ReleaseDetail(
                cycle = cycle.take(100),
                label = firstText(node, "codename")?.take(255),
                releaseDate = parseDate(node.path("releaseDate")),
                eolDate = eolDate,
                supportEndDate = parseDate(node.path("support")),
                alreadyEol = (eolNode.isBoolean && eolNode.asBoolean()) ||
                    (eolDate != null && !eolDate.isAfter(LocalDate.now())),
                eolUnknown = eolDate == null && !eolNode.isBoolean,
                lts = node.path("lts").let { if (it.isBoolean) it.asBoolean() else it.isTextual },
                latestVersion = firstText(node, "latest")?.take(100)
            )
        }
        if (releases.isEmpty()) return null
        return ProductDetail(
            productKey = productKey,
            label = productKey.replace('-', ' ').replaceFirstChar { it.uppercase() }.take(255),
            category = null,
            aliases = emptySet(),
            uri = "$baseUrl/$productKey",
            releases = releases
        )
    }

    private fun firstText(node: JsonNode, vararg fields: String): String? {
        for (field in fields) {
            val value = node.path(field)
            if (value.isTextual) {
                val text = value.asText().trim()
                if (text.isNotEmpty()) return text
            }
        }
        return null
    }

    private fun parseDate(node: JsonNode): LocalDate? {
        if (!node.isTextual) return null
        return try {
            LocalDate.parse(node.asText().trim())
        } catch (e: DateTimeParseException) {
            null
        }
    }

    // ------------------------------------------------------------------- HTTP

    private fun getJson(path: String): JsonNode {
        val uri = buildAndValidateUri(path)
        val request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Accept", "application/json")
            .header("User-Agent", "secman-eol-sync")
            .GET()
            .build()

        val response: HttpResponse<ByteArray> = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: Exception) {
            // Never surface the raw exception text to a client (§A05); the detail
            // stays in the server log.
            log.warn("EOL source request to {} failed: {}", uri.path, e.message)
            throw EolSourceException("EOL source request failed")
        }

        if (response.statusCode() !in 200..299) {
            log.warn("EOL source returned HTTP {} for {}", response.statusCode(), uri.path)
            throw EolSourceException("EOL source returned HTTP ${response.statusCode()}")
        }
        val body = response.body() ?: ByteArray(0)
        if (body.size > maxResponseBytes) {
            throw EolSourceException("EOL source response exceeded ${maxResponseBytes} bytes")
        }
        return try {
            objectMapper.readTree(body)
        } catch (e: Exception) {
            log.warn("EOL source returned unparseable JSON for {}: {}", uri.path, e.message)
            throw EolSourceException("EOL source returned unparseable JSON")
        }
    }

    /**
     * Build the request URI and re-validate it every call. Validating once at
     * startup would not help: DNS can change under a long-lived process, so the
     * address check has to happen at request time.
     */
    fun buildAndValidateUri(path: String): URI {
        val base = baseUrl.trim().trimEnd('/')
        val uri = try {
            URI("$base$path")
        } catch (e: Exception) {
            throw EolSourceException("EOL source base URL is not a valid URL")
        }
        if (!uri.isAbsolute) throw EolSourceException("EOL source base URL must be absolute")
        if (!"https".equals(uri.scheme, ignoreCase = true)) {
            throw EolSourceException("EOL source base URL must use https")
        }
        if (uri.userInfo != null) throw EolSourceException("EOL source base URL must not contain credentials")
        if (uri.port != -1 && uri.port != 443) {
            throw EolSourceException("EOL source base URL must use the default https port")
        }
        val host = uri.host ?: throw EolSourceException("EOL source base URL has no host")
        val allowed = allowedHosts.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (!allowed.contains(host.lowercase())) {
            throw EolSourceException("EOL source host is not in secman.eol.allowed-hosts")
        }
        assertPublicHost(host)
        return uri
    }

    private fun assertPublicHost(host: String) {
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            throw EolSourceException("EOL source host could not be resolved")
        }
        for (address in addresses) {
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
                address.isAnyLocalAddress || address.isMulticastAddress ||
                address.hostAddress == CLOUD_METADATA_IPV4
            ) {
                throw EolSourceException("EOL source host resolves to a non-public address")
            }
        }
    }

    /**
     * Product keys go into the request path, so they are constrained to the
     * character class upstream actually uses. Rejects traversal and any encoded
     * form of it rather than escaping it (§A03).
     */
    fun sanitizeProductKey(raw: String): String {
        val trimmed = raw.trim().lowercase()
        if (trimmed.isEmpty() || trimmed.length > 190) {
            throw EolSourceException("Invalid EOL product key")
        }
        if (!PRODUCT_KEY_PATTERN.matches(trimmed)) {
            throw EolSourceException("Invalid EOL product key")
        }
        // The character class already excludes '/', but "a..b" would pass it and
        // a future path template could make that a traversal. Reject outright.
        if (trimmed.contains("..")) {
            throw EolSourceException("Invalid EOL product key")
        }
        return trimmed
    }

    companion object {
        private const val CLOUD_METADATA_IPV4 = "169.254.169.254"
        private val PRODUCT_KEY_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,189}$")
    }
}
