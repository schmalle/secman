package com.secman.dto

import com.secman.domain.BannerStatus
import com.secman.domain.MaintenanceBanner
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

/**
 * Response DTO for maintenance banner operations.
 *
 * Includes all banner data plus computed fields (status, isActive).
 * Timestamps are serialized as ISO-8601 strings for frontend consumption.
 *
 * @property id Banner unique identifier
 * @property message Maintenance message text (sanitized)
 * @property startTime UTC timestamp when banner becomes active (ISO-8601)
 * @property endTime UTC timestamp when banner deactivates (ISO-8601)
 * @property createdAt UTC timestamp when banner was created
 * @property createdByUsername Username of creator (null if user deleted)
 * @property status Current banner status (UPCOMING, ACTIVE, EXPIRED)
 * @property isActive True if banner should currently display
 */
@Serdeable
data class MaintenanceBannerResponse(
    val id: Long,
    val message: String,
    val startTime: Instant,  // Serialized as ISO-8601 string
    val endTime: Instant,    // Frontend converts to local timezone
    val createdAt: Instant,
    val createdByUsername: String?,  // Username of creator (may be null if user deleted)
    val status: BannerStatus,        // UPCOMING, ACTIVE, EXPIRED
    val isActive: Boolean            // True if currently displaying
) {
    companion object {
        /**
         * Convert MaintenanceBanner entity to response DTO.
         *
         * @param banner The entity to convert
         * @return MaintenanceBannerResponse with computed fields
         */
        fun from(banner: MaintenanceBanner): MaintenanceBannerResponse {
            return MaintenanceBannerResponse(
                id = banner.id!!,
                message = banner.message,
                startTime = banner.startTime,
                endTime = banner.endTime,
                createdAt = banner.createdAt,
                createdByUsername = banner.createdBy?.username,
                status = banner.getStatus(),
                isActive = banner.isActive()
            )
        }
    }
}
