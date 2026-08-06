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
 * Low-level Telegram transport: `POST https://api.telegram.org/bot<token>/sendMessage`.
 *
 * Security notes:
 *  - **The bot token goes in the URL path**, so it is validated against Telegram's
 *    `<digits>:<token>` shape before the URL is built. Without that check a token
 *    containing `/` or `..` would let a stored value redirect the request to another
 *    path on the API host — validation here is not cosmetic.
 *  - The API host itself is fixed configuration, never user input, so there is no
 *    SSRF surface equivalent to Slack's per-user webhook URL.
 *  - Redirects are never followed.
 *  - Messages are sent with no `parse_mode`: Telegram's MarkdownV2 reserves 18
 *    characters and rejects the whole message on a single unescaped one, so imported
 *    data (hostnames, filenames) is far safer sent as plain text.
 *  - Failures are returned, never thrown, and error text is truncated.
 */
@Singleton
open class TelegramClient(
    private val objectMapper: ObjectMapper,

    @Value("\${secman.telegram.api-base-url:https://api.telegram.org}")
    private val apiBaseUrl: String,

    @Value("\${secman.telegram.timeout-seconds:10}")
    private val timeoutSeconds: Long
) {
    private val log = LoggerFactory.getLogger(TelegramClient::class.java)

    companion object {
        /** Telegram bot tokens are `<bot id digits>:<35-char alphanumeric secret>`. */
        private val BOT_TOKEN_PATTERN = Regex("^[0-9]{1,20}:[A-Za-z0-9_-]{20,255}$")

        /**
         * A numeric chat id (negative for groups/supergroups) or an `@publicchannel`
         * username. Anything else is rejected.
         */
        private val CHAT_ID_PATTERN = Regex("^(-?[0-9]{1,20}|@[A-Za-z][A-Za-z0-9_]{4,31})$")
    }

    private val httpClient: JdkHttpClient = JdkHttpClient.newBuilder()
        .version(JdkHttpClient.Version.HTTP_1_1)
        .followRedirects(JdkHttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()

    /**
     * @return null when [token] has Telegram's bot-token shape, otherwise a message safe
     *   to show the user. Must pass before the token is ever placed in a URL path.
     */
    open fun validateBotToken(token: String): String? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return "Bot token must not be empty"
        if (!BOT_TOKEN_PATTERN.matches(trimmed)) {
            return "Bot token must look like 123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ (from @BotFather)"
        }
        return null
    }

    /**
     * @return null when [chatId] is a usable chat id or public channel username,
     *   otherwise a message safe to show the user.
     */
    open fun validateChatId(chatId: String): String? {
        val trimmed = chatId.trim()
        if (trimmed.isEmpty()) return "Chat ID must not be empty"
        if (!CHAT_ID_PATTERN.matches(trimmed)) {
            return "Chat ID must be a numeric ID (e.g. 123456789 or -1001234567890) or an @channelname"
        }
        return null
    }

    /**
     * Send a plain-text message. Telegram answers `sendMessage` with HTTP 200 only on
     * success, but also carries an `ok` flag and a `description` for failures — both are
     * consulted so the user sees Telegram's own wording ("chat not found", "bot was
     * blocked by the user") rather than a bare status code.
     */
    open fun sendMessage(botToken: String, chatId: String, text: String): ChatDeliveryResult {
        validateBotToken(botToken)?.let { return ChatDeliveryResult.failed(it) }
        validateChatId(chatId)?.let { return ChatDeliveryResult.failed(it) }

        val body = objectMapper.writeValueAsString(
            mapOf(
                "chat_id" to chatId.trim(),
                "text" to text,
                "disable_web_page_preview" to true
            )
        )
        return try {
            val request = JdkHttpRequest.newBuilder()
                .uri(URI("${apiBaseUrl.trimEnd('/')}/bot${botToken.trim()}/sendMessage"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(JdkHttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = httpClient.send(request, BodyHandlers.ofString())
            val json = try {
                objectMapper.readTree(response.body())
            } catch (e: Exception) {
                null
            }

            if (json != null && json.path("ok").asBoolean(false)) {
                ChatDeliveryResult.ok()
            } else {
                val description = json?.path("description")?.asText(null)
                ChatDeliveryResult.failed(
                    "Telegram API error (HTTP ${response.statusCode()})" +
                        if (description.isNullOrBlank()) "" else ": $description"
                )
            }
        } catch (e: Exception) {
            // Scrub before anything is logged or persisted: the token is in the request URI,
            // and several JDK HttpClient exceptions echo the URI in their message.
            val detail = redactToken(e.message, botToken)
            log.warn("Telegram sendMessage failed: {}", detail)
            ChatDeliveryResult.failed("Telegram API call failed: $detail")
        }
    }

    /**
     * Remove the bot token from text that is about to be logged or stored.
     *
     * Not defensive decoration: `last_delivery_error` is persisted and rendered back into the
     * UI, and CLAUDE.md A02 forbids a credential reaching any value that lands in a log.
     */
    private fun redactToken(message: String?, botToken: String): String {
        val text = message ?: "unknown error"
        val token = botToken.trim()
        if (token.isEmpty()) return text
        return text.replace(token, "***")
    }
}
