package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.core.JsonParser
import com.secman.dto.SupplierQuestionnaire
import com.secman.dto.SupplierSubmission
import com.secman.dto.SUPPLIER_MAX_QUESTIONS
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

@Singleton
open class SupplierPortalClient(
    private val mapper: ObjectMapper,
    @Value("\${SECMAN_SUPPLIER_PORTAL_ORIGIN:}") private val portalOrigin: String,
    @Value("\${SECMAN_SUPPLIER_INTEGRATION_ORIGIN:}") private val integrationOrigin: String,
    @Value("\${SECMAN_SUPPLIER_CLIENT_KEYSTORE:}") private val keyStorePath: String,
    @Value("\${SECMAN_SUPPLIER_CLIENT_KEYSTORE_PASSWORD:}") private val keyStorePassword: String,
    @Value("\${SECMAN_SUPPLIER_SERVER_TRUSTSTORE:}") private val trustStorePath: String,
    @Value("\${SECMAN_SUPPLIER_SERVER_TRUSTSTORE_PASSWORD:}") private val trustStorePassword: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val integration by lazy { validateIntegrationOrigin(integrationOrigin) }
    private val origin by lazy { httpsOrigin(portalOrigin) }
    private val client by lazy {
        HttpClient.newBuilder()
            .sslContext(createTlsContext())
            .proxy(ProxySelector.of(null))
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build()
    }

    open fun ready(): Boolean = try {
        origin
        integration
        client
        true
    } catch (e: Exception) {
        log.warn("Supplier portal configuration actor=internal outcome=invalid type={}", e.javaClass.simpleName)
        false
    }

    open fun invitationLink(id: String, credential: String): String = "$origin/#$id.$credential"

    open fun provision(
        questionnaire: SupplierQuestionnaire, verifier: String, previousId: String?, retainDraft: Boolean, expiresAt: Instant
    ): Boolean = put(
        questionnaire.invitationId, "",
        mapOf(
            "questionnaire" to questionnaire, "verifier" to verifier,
            "previousInvitationId" to previousId.orEmpty(), "retainDraft" to retainDraft, "expiresAt" to expiresAt.toString()
        )
    )

    open fun revoke(id: String): Boolean = put(id, "/revocation", emptyMap<String, String>())

    open fun readSubmission(id: String): SupplierPortalRead {
        val request = HttpRequest.newBuilder(invitationEndpoint(id, "/submission"))
            .timeout(Duration.ofSeconds(8)).header("Accept", "application/json").GET().build()
        val future = client.sendAsync(request) { info ->
            SupplierResponseBody(if (info.statusCode() == 200) 5 * 1024 * 1024 else 1024)
        }
        return try {
            val response = future.get(8, TimeUnit.SECONDS)
            when (response.statusCode()) {
                204 -> SupplierPortalRead.Waiting
                404 -> SupplierPortalRead.Missing
                200 -> decodeSubmission(response.body(), response.headers().firstValue("Content-Type").orElse(""))
                else -> SupplierPortalRead.Unavailable
            }
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            SupplierPortalRead.Unavailable
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            SupplierPortalRead.Unavailable
        } catch (e: java.util.concurrent.ExecutionException) {
            if (generateSequence(e as Throwable?) { it.cause }.any { it is SupplierResponseTooLarge }) {
                SupplierPortalRead.Invalid
            } else SupplierPortalRead.Unavailable
        }
    }

    internal fun decodeSubmission(body: ByteArray, rawContentType: String): SupplierPortalRead {
        val contentType = rawContentType.substringBefore(';').trim()
        if (!contentType.equals("application/json", true)) return SupplierPortalRead.Invalid
        return try {
            val submission = mapper.readerFor(SupplierSubmission::class.java)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .readValue<SupplierSubmission?>(body)
            if (submission == null || submission.answers.size !in 1..SUPPLIER_MAX_QUESTIONS ||
                (submission.answers as List<*>).any { it == null }
            ) SupplierPortalRead.Invalid else SupplierPortalRead.Completed(submission)
        } catch (_: java.io.IOException) {
            // The poller records a bounded error label without recording supplier content.
            SupplierPortalRead.Invalid
        }
    }

    private fun put(id: String, suffix: String, payload: Any): Boolean {
        val request = HttpRequest.newBuilder(invitationEndpoint(id, suffix))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload)))
            .build()
        return try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream()).let {
                // A compromised portal can send an arbitrary response body. This contract only uses the status.
                it.body().close()
                it.statusCode() == 204
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("Supplier portal actor=internal target={} outcome=interrupted", id)
            false
        } catch (e: java.io.IOException) {
            log.warn("Supplier portal actor=internal target={} outcome=unavailable type={}", id, e.javaClass.simpleName)
            false
        }
    }

    private fun invitationEndpoint(id: String, suffix: String): URI {
        require(ID.matches(id))
        return integration.resolve("/integration/v1/invitations/$id$suffix")
    }

    private fun createTlsContext(): SSLContext {
        val keyPassword = keyStorePassword.toCharArray()
        val trustPassword = trustStorePassword.toCharArray()
        try {
            val keyStore = loadStore(keyStorePath, keyPassword)
            val trustStore = loadStore(trustStorePath, trustPassword)
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keys.init(keyStore, keyPassword)
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(trustStore)
            return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        } finally {
            keyPassword.fill('\u0000')
            trustPassword.fill('\u0000')
        }
    }

    private fun loadStore(path: String, password: CharArray): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(Path.of(path)).use { load(it, password) }
        }

    companion object {
        private val ID = Regex("[a-f0-9-]{36}")

        private fun httpsOrigin(raw: String): URI = URI(raw).also {
            require(it.scheme == "https" && it.host != null && it.rawUserInfo == null &&
                it.rawPath.isNullOrEmpty() && it.rawQuery == null && it.rawFragment == null)
        }

        internal fun validateIntegrationOrigin(raw: String): URI = httpsOrigin(raw).also {
            // Pin an operator-configured public IPv4 destination so DNS rebinding cannot move an mTLS request.
            val parts = it.host.split('.').map { text ->
                text.toIntOrNull()?.takeIf { octet -> octet in 0..255 && octet.toString() == text }
                    ?: throw IllegalArgumentException("Integration requires a public IPv4 address")
            }
            require(parts.size == 4 && (it.port == -1 || it.port in 1..65535))
            val (first, second, third) = parts
            require(first in 1..223 && first !in setOf(10, 127) &&
                !(first == 100 && second in 64..127) && !(first == 169 && second == 254) &&
                !(first == 172 && second in 16..31) && !(first == 192 && second in setOf(0, 168)) &&
                !(first == 198 && second in setOf(18, 19)) &&
                !(first == 192 && second == 88 && third == 99) &&
                !(first == 198 && second == 51 && third == 100) &&
                !(first == 203 && second == 0 && third == 113))
        }
    }
}

sealed interface SupplierPortalRead {
    data object Waiting : SupplierPortalRead
    data object Missing : SupplierPortalRead
    data object Invalid : SupplierPortalRead
    data object Unavailable : SupplierPortalRead
    class Completed(val submission: SupplierSubmission) : SupplierPortalRead
}

internal class SupplierResponseTooLarge : java.io.IOException("Supplier response exceeds the configured limit")

/** Cancel the transport as soon as the next buffer would exceed the response limit. */
internal class SupplierResponseBody(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val bytes = ByteArrayOutputStream()
    private val result = CompletableFuture<ByteArray>()
    private lateinit var subscription: Flow.Subscription

    override fun getBody() = result

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(1)
    }

    override fun onNext(buffers: List<ByteBuffer>) {
        for (buffer in buffers) {
            if (buffer.remaining() > limit - bytes.size()) {
                result.completeExceptionally(SupplierResponseTooLarge())
                subscription.cancel()
                return
            }
            val chunk = ByteArray(buffer.remaining())
            buffer.get(chunk)
            bytes.writeBytes(chunk)
        }
        subscription.request(1)
    }

    override fun onError(error: Throwable) { result.completeExceptionally(error) }
    override fun onComplete() { result.complete(bytes.toByteArray()) }
}
