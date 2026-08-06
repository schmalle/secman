package com.secman.controller

import com.secman.domain.SlackConfig
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
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Workspace-level Slack configuration (ADMIN only).
 *
 * Optional: users can receive Slack notifications through their own incoming webhook
 * without any of this. Configuring a bot token here additionally lets users pick a
 * channel by name instead of pasting a webhook URL.
 *
 * The bot token is a workspace-wide credential and is never returned — the API reports
 * only whether one is stored, and accepts [SlackConfig.TOKEN_MASK] back to mean "keep it".
 */
@Singleton
@Controller("/api/slack/config")
@Secured("ADMIN")
open class SlackConfigController(
    private val slackConfigService: SlackConfigService,
    private val slackClient: SlackClient
) {
    private val log = LoggerFactory.getLogger(SlackConfigController::class.java)

    @Serdeable
    data class SlackConfigResponse(
        val enabled: Boolean,
        val botTokenConfigured: Boolean,
        val defaultChannel: String?
    )

    @Serdeable
    data class UpdateSlackConfigRequest(
        val enabled: Boolean = false,
        /**
         * Omit (null) or send [SlackConfig.TOKEN_MASK] to keep the stored token; send an
         * empty string to clear it.
         */
        val botToken: String? = null,
        val defaultChannel: String? = null
    )

    @Serdeable
    data class ErrorResponse(val error: String)

    @Serdeable
    data class TestResultResponse(val success: Boolean, val message: String)

    @Get
    open fun getConfig(): SlackConfigResponse {
        val config = slackConfigService.find()
        return SlackConfigResponse(
            enabled = config?.enabled ?: false,
            botTokenConfigured = !config?.botToken.isNullOrBlank(),
            defaultChannel = config?.defaultChannel
        )
    }

    @Put
    open fun updateConfig(
        @Body request: UpdateSlackConfigRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        val channel = request.defaultChannel?.trim()?.takeIf { it.isNotEmpty() }
        if (channel != null) {
            slackClient.validateChannel(channel)?.let {
                return HttpResponse.badRequest(ErrorResponse(it))
            }
        }

        // null / mask both mean "leave the stored token alone"; the service treats null
        // that way, so map the mask onto null here.
        val botToken = if (request.botToken == SlackConfig.TOKEN_MASK) null else request.botToken

        if (request.enabled && botToken.isNullOrBlank()) {
            val existing = slackConfigService.find()
            if (existing?.botToken.isNullOrBlank()) {
                return HttpResponse.badRequest(
                    ErrorResponse("A Slack bot token is required to enable workspace delivery")
                )
            }
        }

        val saved = slackConfigService.save(request.enabled, botToken, channel)
        log.info("Slack workspace configuration updated by {}", authentication.name)

        return HttpResponse.ok(
            SlackConfigResponse(
                enabled = saved.enabled,
                botTokenConfigured = !saved.botToken.isNullOrBlank(),
                defaultChannel = saved.defaultChannel
            )
        )
    }

    /**
     * Post a test message to the workspace default channel, verifying the token and the
     * channel in one step.
     */
    @Post("/test")
    open fun testConfig(): HttpResponse<TestResultResponse> {
        val config = slackConfigService.find()
        if (config == null || !config.isBotDeliveryUsable()) {
            return HttpResponse.ok(
                TestResultResponse(false, "Slack workspace delivery is not enabled or no bot token is configured")
            )
        }
        val channel = config.defaultChannel?.trim()?.takeIf { it.isNotEmpty() }
            ?: return HttpResponse.ok(
                TestResultResponse(false, "Set a default channel before sending a test message")
            )

        val result = slackClient.postChatMessage(
            config.botToken!!,
            channel,
            "*SecMan Slack test message*\nWorkspace Slack delivery is configured correctly."
        )
        return HttpResponse.ok(
            TestResultResponse(
                success = result.success,
                message = if (result.success) "Test message sent to $channel" else (result.error ?: "Test message failed")
            )
        )
    }
}
