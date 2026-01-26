package com.secman.controller

import com.secman.service.AppSettingsService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory

/**
 * Controller for application-wide settings.
 * Feature: 068-requirements-alignment-process
 *
 * Provides endpoints for ADMIN users to configure application settings
 * such as the base URL used in email notifications.
 *
 * Access Control: ADMIN role required for all endpoints
 */
@Controller("/api/settings/app")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class AppSettingsController(
    private val appSettingsService: AppSettingsService
) {
    private val logger = LoggerFactory.getLogger(AppSettingsController::class.java)

    @Serdeable
    data class ErrorResponse(
        val status: Int,
        val message: String,
        val timestamp: String = java.time.Instant.now().toString()
    )

    @Serdeable
    data class UpdateAppSettingsRequest(
        @field:NotBlank(message = "Base URL is required")
        val baseUrl: String
    )

    /**
     * GET /api/settings/app
     *
     * Retrieve current application settings.
     *
     * Security: ADMIN role required
     *
     * Returns:
     * - 200 OK: AppSettingsDto { baseUrl: String, updatedBy: String?, updatedAt: String? }
     * - 401 Unauthorized: Missing or invalid JWT token
     * - 403 Forbidden: User does not have ADMIN role
     * - 500 Internal Server Error: Unexpected error
     */
    @Get
    open fun getAppSettings(): HttpResponse<*> {
        return try {
            logger.debug("GET /api/settings/app - Fetching application settings")
            val settings = appSettingsService.getSettings()
            logger.debug("Application settings retrieved: baseUrl={}", settings.baseUrl)
            HttpResponse.ok(settings)
        } catch (e: Exception) {
            logger.error("Failed to retrieve application settings", e)
            HttpResponse.serverError(ErrorResponse(
                status = HttpStatus.INTERNAL_SERVER_ERROR.code,
                message = "Internal server error"
            ))
        }
    }

    /**
     * PUT /api/settings/app
     *
     * Update application settings.
     *
     * Security: ADMIN role required
     *
     * Request Body: { baseUrl: String }
     *
     * Returns:
     * - 200 OK: Updated AppSettingsDto
     * - 400 Bad Request: Invalid base URL format
     * - 401 Unauthorized: Missing or invalid JWT token
     * - 403 Forbidden: User does not have ADMIN role
     * - 500 Internal Server Error: Unexpected error
     */
    @Put
    open fun updateAppSettings(
        @Valid @Body request: UpdateAppSettingsRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            val username = authentication.name
            logger.info("PUT /api/settings/app - User '{}' updating app settings: baseUrl={}",
                username, request.baseUrl)

            val updated = appSettingsService.updateSettings(request.baseUrl, username)

            logger.info("Application settings updated successfully by user '{}'", username)
            HttpResponse.ok(updated)
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid app settings update request: {}", e.message)
            HttpResponse.badRequest(ErrorResponse(
                status = HttpStatus.BAD_REQUEST.code,
                message = e.message ?: "Invalid request"
            ))
        } catch (e: Exception) {
            logger.error("Failed to update application settings", e)
            HttpResponse.serverError(ErrorResponse(
                status = HttpStatus.INTERNAL_SERVER_ERROR.code,
                message = "Internal server error"
            ))
        }
    }
}
