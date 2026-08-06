package com.secman.controller

import com.secman.domain.TelegramConfig
import com.secman.service.TelegramClient
import com.secman.service.TelegramConfigService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Put
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Workspace-level Telegram configuration (ADMIN only).
 *
 * Optional: a user who runs their own bot can store a personal token instead. Configuring
 * a shared bot here means users only need to supply their chat ID.
 *
 * The bot token is a workspace-wide credential and is never returned — the API reports
 * only whether one is stored, and accepts [TelegramConfig.TOKEN_MASK] back to mean "keep it".
 *
 * There is no workspace-level test endpoint (unlike Slack's default channel): a Telegram
 * bot token addresses no conversation on its own, so a meaningful test needs a user's
 * chat ID and therefore belongs on [TelegramSettingsController].
 */
@Singleton
@Controller("/api/telegram/config")
@Secured("ADMIN")
open class TelegramConfigController(
    private val telegramConfigService: TelegramConfigService,
    private val telegramClient: TelegramClient
) {
    private val log = LoggerFactory.getLogger(TelegramConfigController::class.java)

    @Serdeable
    data class TelegramConfigResponse(
        val enabled: Boolean,
        val botTokenConfigured: Boolean
    )

    @Serdeable
    data class UpdateTelegramConfigRequest(
        val enabled: Boolean = false,
        /**
         * Omit (null) or send [TelegramConfig.TOKEN_MASK] to keep the stored token; send an
         * empty string to clear it.
         */
        val botToken: String? = null
    )

    @Serdeable
    data class ErrorResponse(val error: String)

    @Get
    open fun getConfig(): TelegramConfigResponse {
        val config = telegramConfigService.find()
        return TelegramConfigResponse(
            enabled = config?.enabled ?: false,
            botTokenConfigured = !config?.botToken.isNullOrBlank()
        )
    }

    @Put
    open fun updateConfig(
        @Body request: UpdateTelegramConfigRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        // null / mask both mean "leave the stored token alone"; the service treats null
        // that way, so map the mask onto null here.
        val botToken = if (request.botToken == TelegramConfig.TOKEN_MASK) null else request.botToken

        if (!botToken.isNullOrBlank()) {
            // The token is placed in the request URL path, so shape validation here is a
            // security control, not just input hygiene. See TelegramClient.
            telegramClient.validateBotToken(botToken)?.let {
                return HttpResponse.badRequest(ErrorResponse(it))
            }
        }

        if (request.enabled && botToken.isNullOrBlank()) {
            val existing = telegramConfigService.find()
            if (existing?.botToken.isNullOrBlank()) {
                return HttpResponse.badRequest(
                    ErrorResponse("A Telegram bot token is required to enable workspace delivery")
                )
            }
        }

        val saved = telegramConfigService.save(request.enabled, botToken)
        log.info("Telegram workspace configuration updated by {}", authentication.name)

        return HttpResponse.ok(
            TelegramConfigResponse(
                enabled = saved.enabled,
                botTokenConfigured = !saved.botToken.isNullOrBlank()
            )
        )
    }
}
