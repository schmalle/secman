package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Request DTO for creating or updating a maintenance banner.
 *
 * Validation rules:
 * - message: Required, 1-2000 characters
 * - startTime: Required, ISO-8601 timestamp
 * - endTime: Required, ISO-8601 timestamp, must be after startTime
 *
 * @property message Maintenance message text (will be sanitized)
 * @property startTime UTC timestamp when banner becomes active
 * @property endTime UTC timestamp when banner deactivates
 */
@Serdeable
data class MaintenanceBannerRequest(
    @field:NotBlank(message = "Message is required")
    @field:Size(min = 1, max = 2000, message = "Message must be between 1 and 2000 characters")
    val message: String,

    @field:NotNull(message = "Start time is required")
    val startTime: Instant,

    @field:NotNull(message = "End time is required")
    val endTime: Instant
) {
    /**
     * Validate that end time is after start time.
     *
     * @return true if endTime is after startTime, false otherwise
     */
    fun validate(): Boolean {
        return endTime.isAfter(startTime)
    }
}
