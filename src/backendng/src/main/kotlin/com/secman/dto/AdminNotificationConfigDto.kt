package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * DTO for admin notification settings
 * Feature: 027-admin-user-notifications
 *
 * Used for both request (PUT) and response (GET/PUT) from /api/settings/notifications
 */
@Serdeable
data class AdminNotificationConfigDto(
    /**
     * Whether admin notification emails are enabled for new user registrations
     */
    val enabled: Boolean,

    /**
     * Email address used as sender (From field) for notification emails
     */
    @field:NotBlank(message = "Sender email is required")
    @field:Email(message = "Invalid email format")
    @field:Size(min = 5, max = 255, message = "Sender email must be between 5 and 255 characters")
    val senderEmail: String
)
