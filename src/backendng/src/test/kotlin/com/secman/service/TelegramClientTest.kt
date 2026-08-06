package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Covers the pure logic in [TelegramClient] that carries the security weight.
 *
 * The bot token is interpolated into the request URL **path**
 * (`/bot<token>/sendMessage`), so token shape validation is a security control: a stored
 * value containing `/` or `..` would otherwise redirect the request to a different path on
 * the API host. Chat ID validation is the same story for the request body.
 */
class TelegramClientTest {

    private val client = TelegramClient(
        objectMapper = ObjectMapper(),
        apiBaseUrl = "https://api.telegram.org",
        timeoutSeconds = 10
    )

    @Test
    fun `accepts a genuine bot token`() {
        assertThat(client.validateBotToken("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw")).isNull()
    }

    @Test
    fun `rejects bot tokens that could escape the URL path`() {
        // junit-jupiter-params is not on the classpath, so the cases loop inside one @Test.
        val rejected = listOf(
            "" to "empty",
            "   " to "blank",
            "not-a-token" to "no colon",
            "123456789:short" to "secret too short",
            "123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw/../../evil" to "path traversal",
            "123456789:AAHdqTcvCH1vGW/deleteWebhook" to "path injection",
            "123456789:AAHdqTcvCH1vGWJxfSeofSAs?query=1" to "query injection",
            "123456789:AAHdqTcvCH1vGWJxfSeofSAs#frag" to "fragment injection",
            ":AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw" to "missing bot id"
        )

        rejected.forEach { (token, label) ->
            assertThat(client.validateBotToken(token))
                .describedAs("expected token (%s) to be rejected", label)
                .isNotNull()
        }
    }

    @Test
    fun `accepts numeric chat IDs and public channel usernames`() {
        listOf("123456789", "-1001234567890", "@secman_alerts").forEach {
            assertThat(client.validateChatId(it)).describedAs("chat id '%s'", it).isNull()
        }
    }

    @Test
    fun `rejects malformed chat IDs`() {
        listOf("", "   ", "not a chat", "@ab", "12 34", "123;456", "@with-dash").forEach {
            assertThat(client.validateChatId(it)).describedAs("chat id '%s'", it).isNotNull()
        }
    }

    @Test
    fun `sendMessage refuses a malformed token without attempting a request`() {
        // No network is available in a unit test, so a failure naming the validation
        // message (rather than a connection error) proves the guard ran first.
        val result = client.sendMessage("../../evil", "123456789", "hi")

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("Bot token must look like")
    }

    @Test
    fun `a failed send never leaks the bot token into the error text`() {
        // The token is in the request URI, and the returned error is both logged and
        // persisted into last_delivery_error, which is rendered back into the UI.
        // api.invalid does not resolve, so this exercises the real exception path.
        val offline = TelegramClient(ObjectMapper(), "https://api.invalid", 1)
        val token = "123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"

        val result = offline.sendMessage(token, "123456789", "hi")

        assertThat(result.success).isFalse()
        assertThat(result.error).doesNotContain(token)
        assertThat(result.error).doesNotContain("AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw")
    }

    @Test
    fun `sendMessage refuses a malformed chat ID without attempting a request`() {
        val result = client.sendMessage("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw", "not a chat", "hi")

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("Chat ID must be")
    }
}
