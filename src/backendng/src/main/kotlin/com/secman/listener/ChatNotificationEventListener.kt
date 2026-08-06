package com.secman.listener

import com.secman.event.ChatNotificationEvent
import com.secman.service.ChatNotificationService
import io.micronaut.runtime.event.annotation.EventListener
import io.micronaut.scheduling.annotation.Async
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Bridges the generic [ChatNotificationEvent] to chat delivery (Slack, Telegram, …).
 *
 * `@Async` is load-bearing: publishers are import paths, and a chat provider outage must
 * never add its HTTP timeout to an import's latency — nor let a delivery failure escape
 * into the publishing transaction.
 */
@Singleton
open class ChatNotificationEventListener(
    private val chatNotificationService: ChatNotificationService
) {
    private val log = LoggerFactory.getLogger(ChatNotificationEventListener::class.java)

    @EventListener
    @Async
    open fun onChatNotification(event: ChatNotificationEvent) {
        try {
            chatNotificationService.dispatch(event)
        } catch (e: Exception) {
            log.error("Failed to dispatch chat notification for {}", event.eventType, e)
        }
    }
}
