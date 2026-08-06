package com.secman.controller

import com.secman.domain.NotificationChannel
import com.secman.domain.NotificationEventType
import com.secman.domain.UserNotificationSubscription
import com.secman.domain.UserSlackSettings
import com.secman.dto.NotificationEventTypeDto
import com.secman.repository.UserNotificationSubscriptionRepository
import com.secman.repository.UserSlackSettingsRepository
import com.secman.service.ChatNotificationService
import com.secman.service.SlackClient
import com.secman.service.SlackConfigService
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
 * Per-user Slack settings: where my Slack notifications go, and which events I want there.
 *
 * Every route acts on the *calling* user only — the user id comes from the authentication
 * token and is never accepted from the request body, so an authenticated user cannot read
 * or rewrite anyone else's Slack destination.
 *
 * The stored webhook URL is a bearer credential and is never returned; the API reports
 * only whether one is configured, and accepts [UserSlackSettings.WEBHOOK_MASK] back to
 * mean "keep it".
 */
@Singleton
@Controller("/api/slack")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class SlackSettingsController(
    private val settingsRepository: UserSlackSettingsRepository,
    private val subscriptionRepository: UserNotificationSubscriptionRepository,
    private val chatNotificationService: ChatNotificationService,
    private val slackConfigService: SlackConfigService,
    private val slackClient: SlackClient
) {
    private val log = LoggerFactory.getLogger(SlackSettingsController::class.java)

    @Serdeable
    data class SlackSettingsResponse(
        val enabled: Boolean,
        /** True when a personal webhook URL is stored. The URL itself is never returned. */
        val webhookUrlConfigured: Boolean,
        val channel: String?,
        val eventTypes: List<String>,
        val lastNotifiedAt: LocalDateTime?,
        val lastDeliveryStatus: String?,
        val lastDeliveryError: String?,
        /**
         * Whether the workspace bot token is usable. When false, a channel alone cannot
         * deliver anything and the UI says so instead of silently dropping messages.
         */
        val workspaceBotAvailable: Boolean,
        val workspaceDefaultChannel: String?,
        val availableEventTypes: List<NotificationEventTypeDto>
    )

    @Serdeable
    data class UpdateSlackSettingsRequest(
        val enabled: Boolean = false,
        /**
         * Omit (null) or send [UserSlackSettings.WEBHOOK_MASK] to keep the stored URL;
         * send an empty string to clear it.
         */
        val webhookUrl: String? = null,
        val channel: String? = null,
        /** Full replacement set of event type names subscribed on Slack. */
        val eventTypes: List<String> = emptyList()
    )

    @Serdeable
    data class ErrorResponse(val error: String)

    @Serdeable
    data class TestResultResponse(val success: Boolean, val message: String)

    @Get("/settings")
    open fun getSettings(authentication: Authentication): SlackSettingsResponse {
        val userId = userId(authentication)
        val settings = settingsRepository.findByUserId(userId).orElse(null)
        val subscriptions = subscriptionRepository.findByUserIdAndChannel(userId, NotificationChannel.SLACK)
        return buildResponse(settings, subscriptions.map { it.eventType })
    }

    @Put("/settings")
    @Transactional
    open fun updateSettings(
        @Body request: UpdateSlackSettingsRequest,
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

        val newWebhookUrl: String? = when {
            request.webhookUrl == null -> existing?.webhookUrl
            request.webhookUrl == UserSlackSettings.WEBHOOK_MASK -> existing?.webhookUrl
            request.webhookUrl.isBlank() -> null
            else -> {
                slackClient.validateWebhookUrl(request.webhookUrl)?.let {
                    return HttpResponse.badRequest(ErrorResponse(it))
                }
                request.webhookUrl.trim()
            }
        }

        val newChannel: String? = request.channel?.trim()?.takeIf { it.isNotEmpty() }
        if (newChannel != null) {
            slackClient.validateChannel(newChannel)?.let {
                return HttpResponse.badRequest(ErrorResponse(it))
            }
        }

        val saved = if (existing == null) {
            settingsRepository.save(
                UserSlackSettings(
                    userId = userId,
                    enabled = request.enabled,
                    webhookUrl = newWebhookUrl,
                    channel = newChannel
                )
            )
        } else {
            existing.enabled = request.enabled
            existing.webhookUrl = newWebhookUrl
            existing.channel = newChannel
            settingsRepository.update(existing)
        }

        replaceSubscriptions(userId, requestedTypes)

        log.info(
            "Updated Slack settings for user {}: enabled={}, webhook={}, channel={}, events={}",
            userId, saved.enabled, newWebhookUrl != null, newChannel != null, requestedTypes.map { it.name }
        )

        return HttpResponse.ok(buildResponse(saved, requestedTypes))
    }

    /**
     * Send a test message to the caller's own Slack destination so they can confirm the
     * setup works without waiting for a real import.
     */
    @Post("/settings/test")
    open fun sendTest(authentication: Authentication): HttpResponse<TestResultResponse> {
        val result = chatNotificationService.sendTestMessage(
            userId(authentication),
            NotificationChannel.SLACK,
            "*SecMan Slack test message*\nYour Slack notification settings are working."
        )
        return HttpResponse.ok(
            TestResultResponse(
                success = result.success,
                message = if (result.success) "Test message sent" else (result.error ?: "Test message failed")
            )
        )
    }

    /**
     * Replace the Slack subscription set: delete rows no longer wanted, insert the new
     * ones. Deliberately a diff rather than delete-all-then-insert so `created_at` survives
     * on subscriptions the user kept — and, more importantly, scoped to
     * [NotificationChannel.SLACK] so saving Slack settings never disturbs the user's
     * Telegram subscriptions.
     */
    private fun replaceSubscriptions(userId: Long, wanted: List<NotificationEventType>) {
        val current = subscriptionRepository.findByUserIdAndChannel(userId, NotificationChannel.SLACK)
        val currentTypes = current.map { it.eventType }.toSet()

        current.filter { it.eventType !in wanted }.forEach { subscriptionRepository.delete(it) }
        wanted.filter { it !in currentTypes }.forEach {
            subscriptionRepository.save(
                UserNotificationSubscription(
                    userId = userId,
                    channel = NotificationChannel.SLACK,
                    eventType = it
                )
            )
        }
    }

    private fun buildResponse(
        settings: UserSlackSettings?,
        eventTypes: List<NotificationEventType>
    ): SlackSettingsResponse {
        val config = slackConfigService.find()
        return SlackSettingsResponse(
            enabled = settings?.enabled ?: false,
            webhookUrlConfigured = !settings?.webhookUrl.isNullOrBlank(),
            channel = settings?.channel,
            eventTypes = eventTypes.map { it.name },
            lastNotifiedAt = settings?.lastNotifiedAt,
            lastDeliveryStatus = settings?.lastDeliveryStatus,
            lastDeliveryError = settings?.lastDeliveryError,
            workspaceBotAvailable = config?.isBotDeliveryUsable() ?: false,
            workspaceDefaultChannel = config?.defaultChannel,
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
