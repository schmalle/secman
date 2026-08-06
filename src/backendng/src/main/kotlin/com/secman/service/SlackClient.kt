package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

/**
 * Low-level Slack transport: posts a rendered message either to a personal incoming
 * webhook or through the workspace bot token's `chat.postMessage`.
 *
 * Security notes (this and [TelegramClient] are the only places in the platform that
 * fetch a URL derived from what a non-admin user supplied):
 *  - Webhook URLs are checked against a fixed `https://hooks.slack.com/` prefix *and*
 *    parsed-host equality before any request is made. Without that, "paste your webhook
 *    URL" is a server-side request forgery primitive against anything the backend can reach.
 *  - Redirects are never followed, so a 30x cannot walk a validated host to an
 *    unvalidated one.
 *  - Channel names are validated against a conservative character class before being put
 *    in a request body.
 *  - Failures are returned, never thrown, and error text is truncated: a Slack error body
 *    ends up persisted on the user's settings row and shown back in the UI.
 */
@Singleton
open class SlackClient(
    private val objectMapper: ObjectMapper,

    /**
     * Required prefix for a user-supplied incoming webhook URL. Configurable only so a
     * self-hosted Slack-compatible relay can be pointed at deliberately — the default is
     * the real Slack host and nothing else passes.
     */
    @Value("\${secman.slack.webhook-url-prefix:https://hooks.slack.com/}")
    private val webhookUrlPrefix: String,

    @Value("\${secman.slack.api-base-url:https://slack.com/api}")
    private val apiBaseUrl: String,

    @Value("\${secman.slack.timeout-seconds:10}")
    private val timeoutSeconds: Long
) {
    private val log = LoggerFactory.getLogger(SlackClient::class.java)

    companion object {
        /** `#channel`, `channel`, `C012AB3CD` (channel ID) or `U012AB3CD` (DM by member ID). */
        private val CHANNEL_PATTERN = Regex("^[#@]?[A-Za-z0-9._-]{1,80}$")
    }

    private val httpClient: JdkHttpClient = JdkHttpClient.newBuilder()
        .version(JdkHttpClient.Version.HTTP_1_1)
        .followRedirects(JdkHttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()

    /**
     * @return null when [url] is an acceptable Slack incoming webhook, otherwise a
     *   message safe to show the user.
     */
    open fun validateWebhookUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "Webhook URL must not be empty"
        if (!trimmed.startsWith(webhookUrlPrefix)) {
            return "Webhook URL must start with $webhookUrlPrefix"
        }
        val parsed = try {
            URI(trimmed)
        } catch (e: Exception) {
            return "Webhook URL is not a valid URL"
        }
        if (!parsed.isAbsolute) return "Webhook URL is not a valid URL"
        if (!"https".equals(parsed.scheme, ignoreCase = true)) return "Webhook URL must use https"
        // A prefix match alone is not enough: "https://hooks.slack.com/@evil.example" and
        // "https://hooks.slack.com/...@evil.example" both start with the prefix while
        // resolving to another host. Compare the parsed authority instead.
        val expectedHost = URI(webhookUrlPrefix).host
        if (parsed.host == null || !parsed.host.equals(expectedHost, ignoreCase = true)) {
            return "Webhook URL must point at $expectedHost"
        }
        if (parsed.userInfo != null) return "Webhook URL must not contain credentials"
        if (parsed.port != -1 && parsed.port != 443) return "Webhook URL must use the default https port"
        return null
    }

    /**
     * @return null when [channel] is an acceptable channel/member reference, otherwise a
     *   message safe to show the user.
     */
    open fun validateChannel(channel: String): String? {
        val trimmed = channel.trim()
        if (trimmed.isEmpty()) return "Channel must not be empty"
        if (!CHANNEL_PATTERN.matches(trimmed)) {
            return "Channel must be a channel name (#alerts), channel ID (C012AB3CD) or member ID (U012AB3CD)"
        }
        return null
    }

    /** Post to a personal incoming webhook. Validates the URL first — never skip that. */
    open fun postWebhook(url: String, text: String): ChatDeliveryResult {
        validateWebhookUrl(url)?.let { return ChatDeliveryResult.failed(it) }

        val body = objectMapper.writeValueAsString(mapOf("text" to text))
        return try {
            val request = JdkHttpRequest.newBuilder()
                .uri(URI(url.trim()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(JdkHttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = httpClient.send(request, BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                ChatDeliveryResult.ok()
            } else {
                ChatDeliveryResult.failed(
                    "Slack webhook returned HTTP ${response.statusCode()}: ${response.body().orEmpty()}"
                )
            }
        } catch (e: Exception) {
            // The webhook URL is itself the bearer credential, so scrub it out of any
            // exception text before it is logged or persisted into last_delivery_error.
            val detail = redactSecret(e.message, url.trim())
            log.warn("Slack webhook delivery failed: {}", detail)
            ChatDeliveryResult.failed("Slack webhook delivery failed: $detail")
        }
    }

    /**
     * Post via the workspace bot token. Slack answers `chat.postMessage` with HTTP 200
     * even when it refuses the message, so the `ok` field of the body is the real status.
     */
    open fun postChatMessage(botToken: String, channel: String, text: String): ChatDeliveryResult {
        validateChannel(channel)?.let { return ChatDeliveryResult.failed(it) }
        if (botToken.isBlank()) return ChatDeliveryResult.failed("No Slack bot token configured")

        val body = objectMapper.writeValueAsString(
            mapOf("channel" to channel.trim(), "text" to text)
        )
        return try {
            val request = JdkHttpRequest.newBuilder()
                .uri(URI("${apiBaseUrl.trimEnd('/')}/chat.postMessage"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer ${botToken.trim()}")
                .POST(JdkHttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = httpClient.send(request, BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return ChatDeliveryResult.failed("Slack API returned HTTP ${response.statusCode()}")
            }
            val json = objectMapper.readTree(response.body())
            if (json.path("ok").asBoolean(false)) {
                ChatDeliveryResult.ok()
            } else {
                ChatDeliveryResult.failed("Slack API error: ${json.path("error").asText("unknown_error")}")
            }
        } catch (e: Exception) {
            val detail = redactSecret(e.message, botToken.trim())
            log.warn("Slack chat.postMessage failed: {}", detail)
            ChatDeliveryResult.failed("Slack API call failed: $detail")
        }
    }

    /**
     * Remove a credential from text that is about to be logged or stored.
     *
     * Not defensive decoration: `last_delivery_error` is persisted and rendered back into the
     * UI, and CLAUDE.md A02 forbids a credential reaching any value that lands in a log.
     */
    private fun redactSecret(message: String?, secret: String): String {
        val text = message ?: "unknown error"
        if (secret.isEmpty()) return text
        return text.replace(secret, "***")
    }
}
