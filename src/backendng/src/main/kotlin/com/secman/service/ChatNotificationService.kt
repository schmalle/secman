package com.secman.service

import com.secman.domain.ChatDeliveryStatus
import com.secman.domain.NotificationChannel
import com.secman.domain.UserSlackSettings
import com.secman.domain.UserTelegramSettings
import com.secman.event.ChatNotificationEvent
import com.secman.repository.UserNotificationSubscriptionRepository
import com.secman.repository.UserSlackSettingsRepository
import com.secman.repository.UserTelegramSettingsRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Fans a [ChatNotificationEvent] out to every user who subscribed to that event type,
 * over each channel they subscribed on.
 *
 * Delivery is best-effort and per-recipient isolated: one user's bad webhook must never
 * stop another user's message, one channel's outage must never suppress the other, and no
 * chat failure may ever propagate back into the import that published the event (which is
 * why the caller is the `@Async` listener).
 */
@Singleton
open class ChatNotificationService(
    private val subscriptionRepository: UserNotificationSubscriptionRepository,
    private val slackSettingsRepository: UserSlackSettingsRepository,
    private val telegramSettingsRepository: UserTelegramSettingsRepository,
    private val slackConfigService: SlackConfigService,
    private val telegramConfigService: TelegramConfigService,
    private val slackClient: SlackClient,
    private val telegramClient: TelegramClient,
    private val renderer: ChatMessageRenderer
) {
    private val log = LoggerFactory.getLogger(ChatNotificationService::class.java)

    data class DispatchResult(
        val channel: NotificationChannel,
        val subscribers: Int,
        val sent: Int,
        val failed: Int,
        val skipped: Int
    )

    /**
     * Resolved delivery target for one user on one channel. A null resolution means the
     * user is subscribed and enabled but has nowhere to send — reported as skipped rather
     * than failed, since it is a configuration gap, not a delivery error.
     */
    private sealed interface Destination {
        data class SlackWebhook(val url: String) : Destination
        data class SlackBotChannel(val token: String, val channel: String) : Destination
        data class TelegramChat(val token: String, val chatId: String) : Destination
    }

    /**
     * Dispatch over every channel. One channel throwing must not suppress the others.
     *
     * Deliberately NOT `@Transactional`: this performs outbound HTTP with a multi-second
     * timeout per recipient, and wrapping it would pin a pooled DB connection for the whole
     * fan-out. (The 2026-07-21 incident was HikariCP pool starvation from exactly that
     * shape.) Each repository call manages its own short transaction instead.
     */
    open fun dispatch(event: ChatNotificationEvent): List<DispatchResult> =
        NotificationChannel.entries.map { channel ->
            try {
                dispatchTo(channel, event)
            } catch (e: Exception) {
                log.error("Dispatch to {} failed for {}", channel, event.eventType, e)
                DispatchResult(channel, 0, 0, 0, 0)
            }
        }

    private fun dispatchTo(channel: NotificationChannel, event: ChatNotificationEvent): DispatchResult {
        val subscriptions = subscriptionRepository.findByChannelAndEventType(channel, event.eventType)
        if (subscriptions.isEmpty()) {
            log.debug("No {} subscribers for {}", channel, event.eventType)
            return DispatchResult(channel, 0, 0, 0, 0)
        }

        val userIds = subscriptions.map { it.userId }.distinct()
        val message = when (channel) {
            NotificationChannel.SLACK -> renderer.renderSlackMarkdown(event)
            NotificationChannel.TELEGRAM -> renderer.renderPlainText(event)
        }

        var sent = 0
        var failed = 0
        var skipped = 0

        userIds.forEach { userId ->
            val outcome = deliver(channel, userId, message)
            when (outcome) {
                Outcome.SENT -> sent++
                Outcome.FAILED -> failed++
                Outcome.SKIPPED -> skipped++
            }
        }

        log.info(
            "{} dispatch for {}: subscribers={}, sent={}, failed={}, skipped={}",
            channel, event.eventType, userIds.size, sent, failed, skipped
        )
        return DispatchResult(channel, userIds.size, sent, failed, skipped)
    }

    private enum class Outcome { SENT, FAILED, SKIPPED }

    private fun deliver(channel: NotificationChannel, userId: Long, message: String): Outcome {
        return when (channel) {
            NotificationChannel.SLACK -> {
                val settings = slackSettingsRepository.findByUserId(userId).orElse(null)
                if (settings == null || !settings.enabled) return Outcome.SKIPPED
                val destination = resolveSlackDestination(settings)
                if (destination == null) {
                    log.debug("User {} is subscribed on Slack but has no destination configured", userId)
                    recordSlackOutcome(settings, ChatDeliveryStatus.SKIPPED, "No Slack destination configured")
                    return Outcome.SKIPPED
                }
                val result = send(destination, message, userId)
                recordSlackOutcome(
                    settings,
                    if (result.success) ChatDeliveryStatus.SENT else ChatDeliveryStatus.FAILED,
                    result.error
                )
                if (result.success) Outcome.SENT else Outcome.FAILED
            }

            NotificationChannel.TELEGRAM -> {
                val settings = telegramSettingsRepository.findByUserId(userId).orElse(null)
                if (settings == null || !settings.enabled) return Outcome.SKIPPED
                val destination = resolveTelegramDestination(settings)
                if (destination == null) {
                    log.debug("User {} is subscribed on Telegram but has no destination configured", userId)
                    recordTelegramOutcome(settings, ChatDeliveryStatus.SKIPPED, "No Telegram destination configured")
                    return Outcome.SKIPPED
                }
                val result = send(destination, message, userId)
                recordTelegramOutcome(
                    settings,
                    if (result.success) ChatDeliveryStatus.SENT else ChatDeliveryStatus.FAILED,
                    result.error
                )
                if (result.success) Outcome.SENT else Outcome.FAILED
            }
        }
    }

    /**
     * The clients return failures rather than throwing; the catch is a backstop so one
     * recipient cannot abort the whole fan-out.
     */
    private fun send(destination: Destination, message: String, userId: Long): ChatDeliveryResult =
        try {
            when (destination) {
                is Destination.SlackWebhook -> slackClient.postWebhook(destination.url, message)
                is Destination.SlackBotChannel ->
                    slackClient.postChatMessage(destination.token, destination.channel, message)
                is Destination.TelegramChat ->
                    telegramClient.sendMessage(destination.token, destination.chatId, message)
            }
        } catch (e: Exception) {
            log.error("Unexpected error delivering to user {}", userId, e)
            ChatDeliveryResult.failed("Unexpected error: ${e.message}")
        }

    /**
     * Personal webhook wins over the workspace bot: it is the user's own explicit choice
     * and works even when no workspace bot is configured. Falling back to the workspace
     * default channel last means "admin set a channel, user only ticked the boxes" works
     * out of the box.
     */
    private fun resolveSlackDestination(settings: UserSlackSettings): Destination? {
        val webhook = settings.webhookUrl?.trim()
        if (!webhook.isNullOrEmpty()) return Destination.SlackWebhook(webhook)

        val config = slackConfigService.find()
        if (config == null || !config.isBotDeliveryUsable()) return null
        val token = config.botToken ?: return null

        val channel = settings.channel?.trim()?.takeIf { it.isNotEmpty() }
            ?: config.defaultChannel?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        return Destination.SlackBotChannel(token, channel)
    }

    /**
     * Telegram always needs the user's own chat id — a token alone addresses nobody. The
     * token is the user's personal bot when they set one, otherwise the workspace bot.
     */
    private fun resolveTelegramDestination(settings: UserTelegramSettings): Destination? {
        val chatId = settings.chatId?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val personalToken = settings.botToken?.trim()?.takeIf { it.isNotEmpty() }
        if (personalToken != null) return Destination.TelegramChat(personalToken, chatId)

        val config = telegramConfigService.find()
        if (config == null || !config.isBotDeliveryUsable()) return null
        val token = config.botToken ?: return null

        return Destination.TelegramChat(token, chatId)
    }

    /**
     * Send a one-off message to a single user's configured destination on one channel,
     * used by the "Send test message" buttons so a user can verify their setup without
     * waiting for a real import.
     *
     * Not `@Transactional`, for the same reason as [dispatch].
     */
    open fun sendTestMessage(
        userId: Long,
        channel: NotificationChannel,
        text: String
    ): ChatDeliveryResult {
        return when (channel) {
            NotificationChannel.SLACK -> {
                val settings = slackSettingsRepository.findByUserId(userId).orElse(null)
                    ?: return ChatDeliveryResult.failed("Slack is not configured for your account")
                if (!settings.enabled) {
                    return ChatDeliveryResult.failed("Slack notifications are disabled for your account")
                }
                val destination = resolveSlackDestination(settings)
                    ?: return ChatDeliveryResult.failed(
                        "No Slack destination configured — set a webhook URL or a channel"
                    )
                val result = send(destination, text, userId)
                recordSlackOutcome(
                    settings,
                    if (result.success) ChatDeliveryStatus.SENT else ChatDeliveryStatus.FAILED,
                    result.error
                )
                result
            }

            NotificationChannel.TELEGRAM -> {
                val settings = telegramSettingsRepository.findByUserId(userId).orElse(null)
                    ?: return ChatDeliveryResult.failed("Telegram is not configured for your account")
                if (!settings.enabled) {
                    return ChatDeliveryResult.failed("Telegram notifications are disabled for your account")
                }
                val destination = resolveTelegramDestination(settings)
                    ?: return ChatDeliveryResult.failed(
                        "No Telegram destination configured — set a chat ID, and a bot token if the " +
                            "workspace bot is unavailable"
                    )
                val result = send(destination, text, userId)
                recordTelegramOutcome(
                    settings,
                    if (result.success) ChatDeliveryStatus.SENT else ChatDeliveryStatus.FAILED,
                    result.error
                )
                result
            }
        }
    }

    private fun recordSlackOutcome(settings: UserSlackSettings, status: String, error: String?) {
        settings.lastNotifiedAt = LocalDateTime.now()
        settings.lastDeliveryStatus = status
        settings.lastDeliveryError = error?.take(500)
        // Bookkeeping only — a failure to record the outcome must not fail the send.
        runCatching { slackSettingsRepository.update(settings) }
            .onFailure { log.warn("Failed to record Slack delivery outcome for user {}", settings.userId, it) }
    }

    private fun recordTelegramOutcome(settings: UserTelegramSettings, status: String, error: String?) {
        settings.lastNotifiedAt = LocalDateTime.now()
        settings.lastDeliveryStatus = status
        settings.lastDeliveryError = error?.take(500)
        runCatching { telegramSettingsRepository.update(settings) }
            .onFailure { log.warn("Failed to record Telegram delivery outcome for user {}", settings.userId, it) }
    }
}
