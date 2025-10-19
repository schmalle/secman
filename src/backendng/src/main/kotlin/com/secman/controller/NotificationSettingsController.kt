package com.secman.controller

import com.secman.dto.AdminNotificationConfigDto
import com.secman.service.AdminNotificationService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.Valid
import org.slf4j.LoggerFactory

/**
 * Controller for admin notification settings
 * Feature: 027-admin-user-notifications
 *
 * Provides endpoints for ADMIN users to configure email notifications
 * sent when new users are created (via OAuth or manual creation).
 *
 * Access Control: ADMIN role required for all endpoints (FR-015)
 */
@Controller("/api/settings/notifications")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class NotificationSettingsController(
    private val adminNotificationService: AdminNotificationService
) {

    private val logger = LoggerFactory.getLogger(NotificationSettingsController::class.java)

    @Serdeable
    data class ErrorResponse(
        val status: Int,
        val message: String,
        val timestamp: String = java.time.Instant.now().toString()
    )

    /**
     * GET /api/settings/notifications
     *
     * Retrieve current notification configuration settings.
     *
     * Security: ADMIN role required
     *
     * Returns:
     * - 200 OK: NotificationSettingsDto { enabled: Boolean, senderEmail: String }
     * - 401 Unauthorized: Missing or invalid JWT token
     * - 403 Forbidden: User does not have ADMIN role
     * - 500 Internal Server Error: Unexpected error
     */
    @Get
    open fun getNotificationSettings(): HttpResponse<*> {
        return try {
            logger.debug("GET /api/settings/notifications - Fetching notification settings")

            val settings = adminNotificationService.getSettings()

            logger.debug("Notification settings retrieved: enabled=${settings.enabled}, sender=${settings.senderEmail}")
            HttpResponse.ok(settings)
        } catch (e: Exception) {
            logger.error("Failed to retrieve notification settings", e)
            HttpResponse.serverError(ErrorResponse(
                status = HttpStatus.INTERNAL_SERVER_ERROR.code,
                message = "Internal server error"
            ))
        }
    }

    /**
     * PUT /api/settings/notifications
     *
     * Update notification configuration (enable/disable notifications and/or change sender email).
     *
     * Security: ADMIN role required
     *
     * Request Body: NotificationSettingsDto { enabled: Boolean, senderEmail: String }
     *
     * Returns:
     * - 200 OK: Updated NotificationSettingsDto
     * - 400 Bad Request: Invalid email format
     * - 401 Unauthorized: Missing or invalid JWT token
     * - 403 Forbidden: User does not have ADMIN role
     * - 500 Internal Server Error: Unexpected error
     */
    @Put
    open fun updateNotificationSettings(
        @Valid @Body dto: AdminNotificationConfigDto,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            val username = authentication.name
            logger.info("PUT /api/settings/notifications - User '$username' updating notification settings: enabled=${dto.enabled}, sender=${dto.senderEmail}")

            val updatedSettings = adminNotificationService.updateSettings(dto, username)

            logger.info("Notification settings updated successfully by user '$username'")
            HttpResponse.ok(updatedSettings)
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid notification settings update request: ${e.message}")
            HttpResponse.badRequest(ErrorResponse(
                status = HttpStatus.BAD_REQUEST.code,
                message = e.message ?: "Invalid request"
            ))
        } catch (e: Exception) {
            logger.error("Failed to update notification settings", e)
            HttpResponse.serverError(ErrorResponse(
                status = HttpStatus.INTERNAL_SERVER_ERROR.code,
                message = "Internal server error"
            ))
        }
    }
}
