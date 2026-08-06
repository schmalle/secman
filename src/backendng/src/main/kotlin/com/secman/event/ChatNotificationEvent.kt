package com.secman.event

import com.secman.domain.NotificationEventType
import java.time.Instant

/**
 * Generic "something reportable happened" event, published wherever the underlying work
 * actually completes and consumed by
 * [com.secman.listener.ChatNotificationEventListener].
 *
 * Publishers know nothing about Slack or Telegram: they describe the event, and delivery,
 * per-user subscription filtering, channel selection and destination resolution all happen
 * downstream. That is what makes the chat support generic — a new reportable event is a
 * new [NotificationEventType] plus one publish call, and a new transport is one client
 * plus one branch in the dispatcher, with no publisher touched either way.
 */
data class ChatNotificationEvent(
    val eventType: NotificationEventType,

    /** Headline line, e.g. "New CrowdStrike report completed". */
    val title: String,

    /** One-line human summary rendered under the title. May be blank. */
    val summary: String = "",

    /** Ordered label/value detail lines rendered as a bullet list. */
    val fields: List<ChatField> = emptyList(),

    val occurredAt: Instant = Instant.now()
) {
    data class ChatField(val label: String, val value: String)
}
