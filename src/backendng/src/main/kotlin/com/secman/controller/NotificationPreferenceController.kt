package com.secman.controller

import com.secman.domain.NotificationPreference
import com.secman.repository.NotificationPreferenceRepository
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Controller for managing user notification preferences
 * Feature 035: Outdated Asset Notification System
 */
@Singleton
@Controller("/api/notification-preferences")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class NotificationPreferenceController(
    private val notificationPreferenceRepository: NotificationPreferenceRepository
) {
    private val logger = LoggerFactory.getLogger(NotificationPreferenceController::class.java)

    /**
     * Get current user's notification preferences
     * Returns default values if no preferences exist
     */
    @Get
    fun getUserPreferences(authentication: Authentication): NotificationPreferenceResponse {
        val userId = getUserIdFromAuthentication(authentication)

        val preference = notificationPreferenceRepository.findByUserId(userId)
            .orElseGet {
                // Return defaults if not found
                NotificationPreference(
                    userId = userId,
                    enableNewVulnNotifications = false
                )
            }

        return NotificationPreferenceResponse.from(preference)
    }

    /**
     * Update current user's notification preferences
     */
    @Put
    open fun updateUserPreferences(
        @Valid @Body request: UpdatePreferenceRequest,
        authentication: Authentication
    ): NotificationPreferenceResponse {
        val userId = getUserIdFromAuthentication(authentication)

        val existing = notificationPreferenceRepository.findByUserId(userId)

        val preference = if (existing.isPresent) {
            // Update existing
            val updated = existing.get().copy(
                enableNewVulnNotifications = request.enableNewVulnNotifications,
                updatedAt = Instant.now()
            )
            notificationPreferenceRepository.update(updated)
            updated
        } else {
            // Create new
            val newPref = NotificationPreference(
                userId = userId,
                enableNewVulnNotifications = request.enableNewVulnNotifications
            )
            notificationPreferenceRepository.save(newPref)
        }

        logger.info("Updated notification preferences for user $userId: enableNewVuln=${request.enableNewVulnNotifications}")

        return NotificationPreferenceResponse.from(preference)
    }

    @Serdeable
    data class UpdatePreferenceRequest(
        val enableNewVulnNotifications: Boolean
    )

    @Serdeable
    data class NotificationPreferenceResponse(
        val id: Long?,
        val userId: Long,
        val enableNewVulnNotifications: Boolean,
        val lastVulnNotificationSentAt: Instant?,
        val createdAt: Instant,
        val updatedAt: Instant
    ) {
        companion object {
            fun from(preference: NotificationPreference) = NotificationPreferenceResponse(
                id = preference.id,
                userId = preference.userId,
                enableNewVulnNotifications = preference.enableNewVulnNotifications,
                lastVulnNotificationSentAt = preference.lastVulnNotificationSentAt,
                createdAt = preference.createdAt,
                updatedAt = preference.updatedAt
            )
        }
    }

    /**
     * Extract user ID from authentication object
     */
    private fun getUserIdFromAuthentication(authentication: Authentication): Long {
        val userId = authentication.attributes["userId"]
        return when (userId) {
            is Long -> userId
            is Int -> userId.toLong()
            is String -> userId.toLong()
            else -> throw IllegalStateException("Unable to determine user ID from authentication")
        }
    }
}
