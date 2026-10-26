package com.secman.cli.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.DefaultHttpClientConfiguration
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.netty.DefaultHttpClient
import jakarta.inject.Singleton
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

/** HTTP side of `show-notified-users` and `notify-users-overdue` (`/api/overdue-notifications`). */
@Singleton
class OverdueNotificationCliService(private val mapper: ObjectMapper) {
    /** Exit codes: 2 bad input, 3 authentication/authorization, 4 backend or transport failure. */
    class Failure(val exitCode: Int, message: String) : RuntimeException(message)

    /** `GET /api/overdue-notifications/users`: the read-only report for one email. */
    fun overview(baseUrl: URI, username: String, password: String, email: String, days: Int): ObjectNode {
        val encoded = URLEncoder.encode(email, StandardCharsets.UTF_8)
        return exchange(baseUrl, username, password) { token ->
            HttpRequest.GET<Any>("/api/overdue-notifications/users?email=$encoded&days=$days").bearerAuth(token)
        }
    }

    /** `POST /api/overdue-notifications/send`: a long read timeout because the backend mails recipients serially. */
    fun send(baseUrl: URI, username: String, password: String, days: Int, dryRun: Boolean, notificationUser: String?): ObjectNode {
        val body = mutableMapOf<String, Any>("thresholdDays" to days, "dryRun" to dryRun)
        if (notificationUser != null) body["notificationUser"] = notificationUser
        return exchange(baseUrl, username, password, readTimeout = Duration.ofMinutes(30)) { token ->
            HttpRequest.POST<Any>("/api/overdue-notifications/send", mapper.writeValueAsString(body))
                .contentType(MediaType.APPLICATION_JSON_TYPE).bearerAuth(token)
        }
    }

    private fun exchange(
        baseUrl: URI, username: String, password: String,
        readTimeout: Duration = Duration.ofMinutes(5),
        request: (String) -> MutableHttpRequest<Any>
    ): ObjectNode {
        val config = DefaultHttpClientConfiguration().apply {
            setConnectTimeout(Duration.ofSeconds(30))
            setReadTimeout(readTimeout)
            setFollowRedirects(false)
        }
        @Suppress("DEPRECATION")
        DefaultHttpClient(baseUrl, config).use { client ->
            val token = CliHttpClient(client, mapper).authenticate(username, password, baseUrl.toString())
                ?: throw Failure(3, "Authentication failed; check credentials, MFA, and backend connectivity")
            val body = try {
                client.toBlocking().retrieve(request(token).accept(MediaType.APPLICATION_JSON_TYPE), String::class.java)
            } catch (e: HttpClientResponseException) {
                throw when (e.status.code) {
                    400 -> Failure(2, "Backend rejected the request: ${errorText(e)}")
                    401, 403 -> Failure(3, "Access denied: an authenticated ADMIN caller is required")
                    else -> Failure(4, "Backend request failed (HTTP ${e.status.code})")
                }
            }
            return mapper.readTree(body) as? ObjectNode ?: throw Failure(4, "Invalid backend response")
        }
    }

    private fun errorText(e: HttpClientResponseException): String =
        runCatching { mapper.readTree(e.response.getBody(String::class.java).orElse("{}")).path("error").asText("") }
            .getOrDefault("")
            .ifBlank { "invalid input" }
            .filterNot { it.isISOControl() }
}
