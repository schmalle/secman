package com.secman.service.aibuddy

import com.secman.service.OutboundUrlPolicy
import jakarta.inject.Singleton
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

class AiBuddyFailure(val code: String, val retryable: Boolean = false, val retryAfter: Long? = null) : RuntimeException(code)
data class AiBuddyHttpResult(val status: Int, val body: ByteArray, val retryAfter: Long?)

@Singleton
open class AiBuddyHttpTransport {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    open fun request(
        uri: URI, hosts: Set<String>, timeoutSeconds: Long, maxBytes: Int,
        body: ByteArray? = null, headers: Map<String, String> = emptyMap()
    ): AiBuddyHttpResult {
        try {
            OutboundUrlPolicy.validate(uri, hosts)
        } catch (e: Exception) {
            throw AiBuddyFailure("SOURCE_UNAVAILABLE")
        }
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Accept", "application/json").header("Accept-Encoding", "identity")
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (body == null) builder.GET() else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body))
        val pending = client.sendAsync(builder.build()) { info ->
            if (info.headers().firstValue("Content-Encoding").orElse("identity") != "identity") {
                throw AiBuddyFailure("EVIDENCE_UNAVAILABLE")
            }
            LimitedBody(maxBytes)
        }
        return try {
            val response = pending.get(timeoutSeconds, TimeUnit.SECONDS)
            AiBuddyHttpResult(response.statusCode(), response.body(), response.headers().firstValue("Retry-After").orElse("").toLongOrNull())
        } catch (e: InterruptedException) {
            pending.cancel(true)
            Thread.currentThread().interrupt()
            throw AiBuddyFailure("CANCELLED")
        } catch (e: Exception) {
            pending.cancel(true)
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is AiBuddyFailure) throw cause
                cause = cause.cause
            }
            throw AiBuddyFailure("PROVIDER_TIMEOUT", true)
        }
    }

    private class LimitedBody(private val maxBytes: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val done = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        private lateinit var subscription: Flow.Subscription
        override fun getBody(): CompletionStage<ByteArray> = done
        override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(1) }
        override fun onNext(items: List<ByteBuffer>) {
            for (item in items) {
                if (item.remaining() > maxBytes - bytes.size()) {
                    subscription.cancel()
                    done.completeExceptionally(AiBuddyFailure("SCOPE_TOO_LARGE"))
                    return
                }
                val chunk = ByteArray(item.remaining())
                item.get(chunk)
                bytes.write(chunk)
            }
            subscription.request(1)
        }
        override fun onError(error: Throwable) { done.completeExceptionally(error) }
        override fun onComplete() { done.complete(bytes.toByteArray()) }
    }
}
