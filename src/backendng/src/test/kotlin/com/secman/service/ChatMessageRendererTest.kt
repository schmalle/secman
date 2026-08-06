package com.secman.service

import com.secman.domain.NotificationEventType
import com.secman.event.ChatNotificationEvent
import com.secman.event.ChatNotificationEvent.ChatField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Rendering is shared by every channel, so both flavours are covered here — including the
 * escaping that stops imported data (hostnames, filenames, account ids) from injecting
 * markup into a Slack message.
 */
class ChatMessageRendererTest {

    private val renderer = ChatMessageRenderer()

    private fun event(
        title: String = "New CrowdStrike report completed",
        summary: String = "12 server(s) processed.",
        fields: List<ChatField> = listOf(ChatField("Servers processed", "12"))
    ) = ChatNotificationEvent(
        eventType = NotificationEventType.CROWDSTRIKE_REPORT_COMPLETED,
        title = title,
        summary = summary,
        fields = fields,
        occurredAt = Instant.parse("2026-08-06T10:15:30Z")
    )

    @Test
    fun `renders Slack mrkdwn with a bold title and bulleted fields`() {
        val message = renderer.renderSlackMarkdown(event())

        assertThat(message).contains("*New CrowdStrike report completed*")
        assertThat(message).contains("12 server(s) processed.")
        assertThat(message).contains("• Servers processed: 12")
    }

    @Test
    fun `escapes Slack markup characters in event content`() {
        // Hostnames and account ids come from imported data, so an event value must not be
        // able to inject a fake Slack link (<http://…|text>) into the rendered message.
        val message = renderer.renderSlackMarkdown(
            event(
                title = "Import <done> & finished",
                summary = "",
                fields = listOf(ChatField("Host", "<https://evil.example|click me>"))
            )
        )

        assertThat(message).doesNotContain("<https://evil.example")
        assertThat(message).contains("&lt;https://evil.example|click me&gt;")
        assertThat(message).contains("Import &lt;done&gt; &amp; finished")
    }

    @Test
    fun `plain text rendering carries the same content without markup`() {
        val message = renderer.renderPlainText(event())

        assertThat(message).startsWith("New CrowdStrike report completed")
        assertThat(message).contains("12 server(s) processed.")
        assertThat(message).contains("• Servers processed: 12")
        // No markup means nothing for Telegram's parser to reject — and nothing to escape.
        assertThat(message).doesNotContain("*")
        assertThat(message).doesNotContain("_")
    }

    @Test
    fun `plain text rendering leaves event content verbatim`() {
        val message = renderer.renderPlainText(
            event(fields = listOf(ChatField("Host", "<https://evil.example|click me>")))
        )

        // Sent with no parse_mode, so there is no markup context to escape into.
        assertThat(message).contains("Host: <https://evil.example|click me>")
    }

    @Test
    fun `omits a blank summary line`() {
        val message = renderer.renderPlainText(event(summary = ""))

        assertThat(message.lines()[1]).startsWith("• ")
    }
}
