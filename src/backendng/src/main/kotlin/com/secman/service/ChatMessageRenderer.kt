package com.secman.service

import com.secman.event.ChatNotificationEvent
import jakarta.inject.Singleton
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders a [ChatNotificationEvent] into the wire format each transport wants.
 *
 * Shared so the two channels can never drift in what they report, while still differing
 * in markup. Event values (hostnames, account ids, filenames) come from imported data, so
 * anything that goes into a markup-bearing format is escaped here — the Slack renderer
 * escapes Slack's three reserved characters, and the plain-text renderer is used for
 * Telegram precisely so no escaping question arises there at all (Telegram's MarkdownV2
 * reserves 18 characters, and a single missed one makes the API reject the whole message).
 */
@Singleton
open class ChatMessageRenderer {

    companion object {
        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
    }

    /** Slack mrkdwn: bold title, bulleted fields, italic timestamp. */
    open fun renderSlackMarkdown(event: ChatNotificationEvent): String {
        val builder = StringBuilder()
        builder.append("*").append(escapeSlack(event.title)).append("*")
        if (event.summary.isNotBlank()) {
            builder.append("\n").append(escapeSlack(event.summary))
        }
        event.fields.forEach { field ->
            builder.append("\n• ").append(escapeSlack(field.label)).append(": ").append(escapeSlack(field.value))
        }
        builder.append("\n_").append(escapeSlack(timestamp(event))).append("_")
        return builder.toString()
    }

    /** Markup-free rendering, sent to Telegram with no `parse_mode`. */
    open fun renderPlainText(event: ChatNotificationEvent): String {
        val builder = StringBuilder()
        builder.append(event.title)
        if (event.summary.isNotBlank()) {
            builder.append("\n").append(event.summary)
        }
        event.fields.forEach { field ->
            builder.append("\n• ").append(field.label).append(": ").append(field.value)
        }
        builder.append("\n").append(timestamp(event))
        return builder.toString()
    }

    private fun timestamp(event: ChatNotificationEvent): String =
        TIMESTAMP_FORMAT.format(event.occurredAt.atZone(ZoneId.systemDefault()))

    /** Slack mrkdwn escaping — `&`, `<` and `>` are the only characters it reserves. */
    private fun escapeSlack(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
