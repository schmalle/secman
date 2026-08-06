package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Covers the pure logic in [SlackClient] that carries the security weight: the webhook URL
 * allowlist. That check is the SSRF boundary — the backend, not the browser, performs the
 * outbound request against a value a non-admin user supplied.
 *
 * The HTTP calls themselves are not exercised here; they would need a live Slack.
 * Rendering lives in [ChatMessageRenderer] and is covered by [ChatMessageRendererTest].
 */
class SlackClientTest {

    private val client = SlackClient(
        objectMapper = ObjectMapper(),
        webhookUrlPrefix = "https://hooks.slack.com/",
        apiBaseUrl = "https://slack.com/api",
        timeoutSeconds = 10
    )

    @Test
    fun `accepts a genuine Slack incoming webhook URL`() {
        assertThat(client.validateWebhookUrl("https://hooks.slack.com/services/T000/B000/XXXX")).isNull()
    }

    @Test
    fun `rejects webhook URLs that are not Slack incoming webhooks`() {
        // junit-jupiter-params is not on the classpath, so the cases loop inside one @Test.
        val rejected = listOf(
            "" to "empty",
            "   " to "blank",
            "http://hooks.slack.com/services/T000/B000/XXXX" to "plain http",
            "https://evil.example/services/T000" to "unrelated host",
            "https://hooks.slack.com.evil.example/services/T000" to "host suffix attack",
            "https://user:pass@hooks.slack.com/services/T000" to "embedded credentials",
            "https://hooks.slack.com:8443/services/T000" to "non-default port",
            "http://169.254.169.254/latest/meta-data/" to "cloud metadata endpoint",
            "file:///etc/passwd" to "non-http scheme",
            "https://localhost/services/T000" to "loopback"
        )

        rejected.forEach { (url, label) ->
            assertThat(client.validateWebhookUrl(url))
                .describedAs("expected '%s' (%s) to be rejected", url, label)
                .isNotNull()
        }
    }

    @Test
    fun `postWebhook refuses a disallowed URL without attempting a request`() {
        // No network is available in a unit test, so a failure here that names the
        // validation message (rather than a connection error) proves the guard ran first.
        val result = client.postWebhook("https://evil.example/hook", "hi")

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("hooks.slack.com")
    }

    @Test
    fun `accepts channel names, channel IDs and member IDs`() {
        listOf("#alerts", "alerts", "C012AB3CD", "U012AB3CD", "@someone", "team-sec_1.2").forEach {
            assertThat(client.validateChannel(it)).describedAs("channel '%s'", it).isNull()
        }
    }

    @Test
    fun `rejects malformed channels`() {
        listOf("", "   ", "has space", "a".repeat(120), "with\"quote", "semi;colon").forEach {
            assertThat(client.validateChannel(it)).describedAs("channel '%s'", it).isNotNull()
        }
    }

    @Test
    fun `postChatMessage refuses a malformed channel without attempting a request`() {
        val result = client.postChatMessage("xoxb-token", "bad channel", "hi")

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("Channel must be")
    }
}
