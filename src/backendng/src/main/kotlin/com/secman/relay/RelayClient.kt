package com.secman.relay

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The only component that talks to the relay.
 *
 * Every request carries three things: the bearer token, a timestamp + nonce,
 * and an HMAC-SHA256 signature over `v1:timestamp:nonce:sha256(body)`. The
 * signature is what makes a leaked bearer token insufficient on its own, and
 * the timestamp + nonce are what make a captured request un-replayable.
 *
 * Modelled on [com.secman.service.SlackClient], which is this codebase's
 * reference for an outbound HTTP call: redirects never followed, hard timeouts,
 * failures returned rather than thrown, and secrets scrubbed from any text that
 * could reach a log.
 *
 * On SSRF (A10): the target URL is operator configuration read from the
 * environment. No user, admin or API caller can influence it at runtime, which
 * is the property that matters — the classic SSRF primitive is a *user-supplied*
 * URL. What is still enforced here is scheme (https unless plaintext is
 * explicitly allowed), no embedded credentials, no redirects, and an outright
 * refusal to reach link-local space, so a typo cannot point the pusher at cloud
 * instance metadata. RFC-1918 targets are deliberately allowed: a relay bound
 * to a private ingest interface is the recommended topology.
 */
@Singleton
open class RelayClient(
    private val properties: RelayProperties,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(RelayClient::class.java)
    private val random = SecureRandom()

    companion object {
        const val HEADER_TIMESTAMP = "X-Secman-Timestamp"
        const val HEADER_NONCE = "X-Secman-Nonce"
        const val HEADER_SIGNATURE = "X-Secman-Signature"
        const val SIGNATURE_VERSION = "v1"

        const val PATH_SNAPSHOT = "/ingest/v1/snapshot"
        const val PATH_CONTROL = "/ingest/v1/control"
        const val PATH_DEVICES = "/ingest/v1/devices"
        const val PATH_STATUS = "/ingest/v1/status"

        /** Bound on the error text kept from a relay response. */
        private const val MAX_ERROR_BODY = 512
    }

    private val httpClient: JdkHttpClient by lazy {
        JdkHttpClient.newBuilder()
            .version(JdkHttpClient.Version.HTTP_1_1)
            .followRedirects(JdkHttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(properties.timeoutSeconds))
            .build()
    }

    /**
     * @return null when the configuration is usable, otherwise a message safe to
     *   show an admin. Called at every publish so a bad value cannot lie dormant.
     */
    open fun validateConfiguration(): String? {
        if (properties.url.isBlank()) return "secman.relay.url is not set"
        if (properties.token.isBlank()) return "secman.relay.token is not set"
        if (properties.hmacKey.isBlank()) return "secman.relay.hmac-key is not set"
        if (properties.token == properties.hmacKey) {
            return "secman.relay.token and secman.relay.hmac-key must be different values"
        }
        if (properties.instanceId.isBlank()) return "secman.relay.instance-id is not set"

        val parsed = try {
            URI(properties.url.trim())
        } catch (e: Exception) {
            return "secman.relay.url is not a valid URL"
        }
        if (!parsed.isAbsolute) return "secman.relay.url must be absolute"

        val scheme = parsed.scheme?.lowercase()
        if (scheme != "https" && !(scheme == "http" && properties.allowPlaintextUrl)) {
            return "secman.relay.url must use https (set secman.relay.allow-plaintext-url=true only for a local development relay)"
        }
        if (parsed.userInfo != null) return "secman.relay.url must not contain credentials"
        val host = parsed.host ?: return "secman.relay.url must contain a host"

        // Link-local, and cloud instance metadata in particular, is never a
        // legitimate relay. Private ranges are allowed on purpose.
        resolveOrNull(host)?.let { address ->
            if (address.isLinkLocalAddress || address.isAnyLocalAddress || address.isMulticastAddress) {
                return "secman.relay.url resolves to a link-local, wildcard or multicast address"
            }
        }
        return null
    }

    /** Pushes a snapshot. */
    open fun pushSnapshot(snapshot: RelaySnapshot): RelayCallResult =
        send("POST", PATH_SNAPSHOT, objectMapper.writeValueAsBytes(snapshot))

    /** Pushes a control document (enrollment grants, revocations). */
    open fun pushControl(control: RelayControl): RelayCallResult =
        send("POST", PATH_CONTROL, objectMapper.writeValueAsBytes(control))

    /** Reads the relay's device list. */
    open fun fetchDevices(): RelayCallResult = send("GET", PATH_DEVICES, ByteArray(0))

    /** Reads the relay's own view of its health and snapshot freshness. */
    open fun fetchStatus(): RelayCallResult = send("GET", PATH_STATUS, ByteArray(0))

    private fun send(method: String, path: String, body: ByteArray): RelayCallResult {
        validateConfiguration()?.let { return RelayCallResult.failed(it) }

        val timestamp = Instant.now().epochSecond
        val nonce = newNonce()
        val signature = signPayload(properties.hmacKey, timestamp, nonce, body)
        val target = URI(properties.url.trim().trimEnd('/') + path)

        return try {
            val builder = JdkHttpRequest.newBuilder()
                .uri(target)
                .timeout(Duration.ofSeconds(properties.timeoutSeconds))
                .header("Authorization", "Bearer ${properties.token}")
                .header(HEADER_TIMESTAMP, timestamp.toString())
                .header(HEADER_NONCE, nonce)
                .header(HEADER_SIGNATURE, signature)
                .header("Content-Type", "application/json")

            // The signature covers the body for every method, including GET —
            // an empty body still has a digest, so the ingest plane has exactly
            // one verification path rather than a special case to get wrong.
            val request = when (method) {
                "GET" -> builder.method("GET", JdkHttpRequest.BodyPublishers.ofByteArray(body)).build()
                else -> builder.POST(JdkHttpRequest.BodyPublishers.ofByteArray(body)).build()
            }

            val response = httpClient.send(request, BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                RelayCallResult.ok(response.statusCode(), response.body().orEmpty())
            } else {
                RelayCallResult.failed(
                    "Relay returned HTTP ${response.statusCode()}: ${truncate(response.body().orEmpty())}",
                    response.statusCode()
                )
            }
        } catch (e: Exception) {
            // The bearer token and the HMAC key are in this object's fields and
            // could surface in an exception message via the URL or a header
            // dump; scrub both before anything is logged or shown to an admin.
            val detail = redact(e.message)
            logger.warn("Relay call to {} failed: {}", path, detail)
            RelayCallResult.failed("Relay call failed: $detail")
        }
    }

    /**
     * Builds the `X-Secman-Signature` value.
     *
     * The canonical string binds the request time, a unique nonce and a digest
     * of the exact body, with unambiguous separators. Signing the digest rather
     * than the body keeps the construction identical for a small control
     * document and a large snapshot.
     */
    internal fun signPayload(key: String, unixSeconds: Long, nonce: String, body: ByteArray): String {
        // SHA-256 here is a message digest inside the HMAC construction, not
        // secret storage — see RelayDigest for why that distinction matters.
        val bodyDigest = RelayDigest.hex(RelayDigest.digestOf(body))
        val canonical = "$SIGNATURE_VERSION:$unixSeconds:$nonce:$bodyDigest"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return "$SIGNATURE_VERSION=${RelayDigest.hex(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))}"
    }

    private fun newNonce(): String {
        val buf = ByteArray(16)
        random.nextBytes(buf)
        return RelayDigest.hex(buf)
    }

    private fun resolveOrNull(host: String): InetAddress? = try {
        InetAddress.getByName(host)
    } catch (e: UnknownHostException) {
        // A name that does not resolve yet is not a configuration error: the
        // relay's DNS may simply not be live. The push itself will report it.
        null
    }

    private fun redact(message: String?): String {
        var text = message ?: "unknown error"
        if (properties.token.isNotEmpty()) text = text.replace(properties.token, "***")
        if (properties.hmacKey.isNotEmpty()) text = text.replace(properties.hmacKey, "***")
        return sanitizeForLog(text)
    }

    private fun truncate(body: String): String = sanitizeForLog(
        if (body.length > MAX_ERROR_BODY) body.take(MAX_ERROR_BODY) + "…" else body
    )
}

/**
 * Strips CR/LF from text that is about to be logged (A09: log forging). Shared
 * by the relay classes; the relay's own responses are the untrusted input here.
 */
internal fun sanitizeForLog(value: String): String =
    value.replace("\r", "").replace("\n", " ").trim()
