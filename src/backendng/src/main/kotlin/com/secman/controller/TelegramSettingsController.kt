package com.secman.controller

import com.secman.domain.NotificationChannel
import com.secman.domain.NotificationEventType
import com.secman.domain.UserNotificationSubscription
import com.secman.domain.UserTelegramSettings
import com.secman.dto.NotificationEventTypeDto
import com.secman.repository.UserNotificationSubscriptionRepository
import com.secman.repository.UserTelegramSettingsRepository
import com.secman.service.ChatNotificationService
import com.secman.service.TelegramClient
import com.secman.service.TelegramConfigService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Put
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Per-user Telegram settings: which chat my Telegram notifications go to, and which
 * events I want there.
 *
 * Mirrors [SlackSettingsController] deliberately — same shape, same masking convention,
 * same per-caller scoping (the user id comes from the authentication token and is never
 * accepted from the body). Subscriptions are stored per channel, so saving here never
 * disturbs the user's Slack subscriptions.
 */
@Singleton
@Controller("/api/telegram")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class TelegramSettingsController(
    private val settingsRepository: UserTelegramSettingsRepository,
    private val subscriptionRepository: UserNotificationSubscriptionRepository,
    private val chatNotificationService: ChatNotificationService,
    private val telegramConfigService: TelegramConfigService,
    private val telegramClient: TelegramClient
) {
    private val log = LoggerFactory.getLogger(TelegramSettingsController::class.java)

    @Serdeable
    data class TelegramSettingsResponse(
        val enabled: Boolean,
        val chatId: String?,
        /** True when a personal bot token is stored. The token itself is never returned. */
        val botTokenConfigured: Boolean,
        val eventTypes: List<String>,
        val lastNotifiedAt: LocalDateTime?,
        val lastDeliveryStatus: String?,
        val lastDeliveryError: String?,
        /**
         * Whether the workspace bot token is usable. When false, a chat ID alone cannot
         * deliver anything and the UI says so instead of silently dropping messages.
         */
        val workspaceBotAvailable: Boolean,
        val availableEventTypes: List<NotificationEventTypeDto>
    )

    @Serdeable
    data class UpdateTelegramSettingsRequest(
        val enabled: Boolean = false,
        val chatId: String? = null,
        /**
         * Omit (null) or send [UserTelegramSettings.TOKEN_MASK] to keep the stored token;
         * send an empty string to clear it and fall back to the workspace bot.
         */
        val botToken: String? = null,
        /** Full replacement set of event type names subscribed on Telegram. */
        val eventTypes: List<String> = emptyList()
    )

    @Serdeable
    data class ErrorResponse(val error: String)

    @Serdeable
    data class TestResultResponse(val success: Boolean, val message: String)

    @Get("/settings")
    open fun getSettings(authentication: Authentication): TelegramSettingsResponse {
        val userId = userId(authentication)
        val settings = settingsRepository.findByUserId(userId).orElse(null)
        val subscriptions = subscriptionRepository.findByUserIdAndChannel(userId, NotificationChannel.TELEGRAM)
        return buildResponse(settings, subscriptions.map { it.eventType })
    }

    @Put("/settings")
    @Transactional
    open fun updateSettings(
        @Body request: UpdateTelegramSettingsRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        val userId = userId(authentication)

        // Validate before writing anything, so a rejected request leaves settings untouched.
        val requestedTypes = mutableListOf<NotificationEventType>()
        for (raw in request.eventTypes) {
            val parsed = NotificationEventType.fromNameOrNull(raw)
                ?: return HttpResponse.badRequest(ErrorResponse("Unknown notification event type: $raw"))
            if (!requestedTypes.contains(parsed)) requestedTypes.add(parsed)
        }

        val existing = settingsRepository.findByUserId(userId).orElse(null)

        val newChatId: String? = request.chatId?.trim()?.takeIf { it.isNotEmpty() }
        if (newChatId != null) {
            telegramClient.validateChatId(newChatId)?.let {
                return HttpResponse.badRequest(ErrorResponse(it))
            }
        }

        val newBotToken: String? = when {
            request.botToken == null -> existing?.botToken
            request.botToken == UserTelegramSettings.TOKEN_MASK -> existing?.botToken
            request.botToken.isBlank() -> null
            else -> {
                // The token is placed in the request URL path, so shape validation here is
                // a security control, not just input hygiene. See TelegramClient.
                telegramClient.validateBotToken(request.botToken)?.let {
                    return HttpResponse.badRequest(ErrorResponse(it))
                }
                request.botToken.trim()
            }
        }

        val saved = if (existing == null) {
            settingsRepository.save(
                UserTelegramSettings(
                    userId = userId,
                    enabled = request.enabled,
                    chatId = newChatId,
                    botToken = newBotToken
                )
            )
        } else {
            existing.enabled = request.enabled
            existing.chatId = newChatId
            existing.botToken = newBotToken
            settingsRepository.update(existing)
        }

        replaceSubscriptions(userId, requestedTypes)

        log.info(
            "Updated Telegram settings for user {}: enabled={}, chatId={}, personalToken={}, events={}",
            userId, saved.enabled, newChatId != null, newBotToken != null, requestedTypes.map { it.name }
        )

        return HttpResponse.ok(buildResponse(saved, requestedTypes))
    }

    @Post("/settings/test")
    open fun sendTest(authentication: Authentication): HttpResponse<TestResultResponse> {
        val result = chatNotificationService.sendTestMessage(
            userId(authentication),
            NotificationChannel.TELEGRAM,
            "SecMan Telegram test message\nYour Telegram notification settings are working."
        )
        return HttpResponse.ok(
            TestResultResponse(
                success = result.success,
                message = if (result.success) "Test message sent" else (result.error ?: "Test message failed")
            )
        )
    }

    /** See [SlackSettingsController.replaceSubscriptions] — same diff, scoped to Telegram. */
    private fun replaceSubscriptions(userId: Long, wanted: List<NotificationEventType>) {
        val current = subscriptionRepository.findByUserIdAndChannel(userId, NotificationChannel.TELEGRAM)
        val currentTypes = current.map { it.eventType }.toSet()

        current.filter { it.eventType !in wanted }.forEach { subscriptionRepository.delete(it) }
        wanted.filter { it !in currentTypes }.forEach {
            subscriptionRepository.save(
                UserNotificationSubscription(
                    userId = userId,
                    channel = NotificationChannel.TELEGRAM,
                    eventType = it
                )
            )
        }
    }

    private fun buildResponse(
        settings: UserTelegramSettings?,
        eventTypes: List<NotificationEventType>
    ): TelegramSettingsResponse {
        val config = telegramConfigService.find()
        return TelegramSettingsResponse(
            enabled = settings?.enabled ?: false,
            chatId = settings?.chatId,
            botTokenConfigured = !settings?.botToken.isNullOrBlank(),
            eventTypes = eventTypes.map { it.name },
            lastNotifiedAt = settings?.lastNotifiedAt,
            lastDeliveryStatus = settings?.lastDeliveryStatus,
            lastDeliveryError = settings?.lastDeliveryError,
            workspaceBotAvailable = config?.isBotDeliveryUsable() ?: false,
            availableEventTypes = NotificationEventTypeDto.catalogue()
        )
    }

    private fun userId(authentication: Authentication): Long {
        return when (val value = authentication.attributes["userId"]) {
            is Long -> value
            is Int -> value.toLong()
            is String -> value.toLong()
            else -> throw IllegalStateException("Unable to determine user ID from authentication")
        }
    }
}
