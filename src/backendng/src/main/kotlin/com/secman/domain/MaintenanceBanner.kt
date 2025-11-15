package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * MaintenanceBanner entity represents a scheduled maintenance notification
 * displayed on the start/login page during a configured time window.
 *
 * Features:
 * - Time-based activation (startTime to endTime in UTC)
 * - Plain text messages (sanitized for XSS prevention)
 * - Multiple concurrent banners support (stacked vertically)
 * - Audit trail (createdBy, createdAt)
 *
 * @property id Unique identifier (auto-generated)
 * @property message Maintenance message text (1-2000 characters, sanitized)
 * @property startTime UTC timestamp when banner becomes active
 * @property endTime UTC timestamp when banner deactivates
 * @property createdAt UTC timestamp when banner was created
 * @property createdBy Admin user who created the banner (nullable for history preservation)
 */
@Entity
@Table(
    name = "maintenance_banner",
    indexes = [
        Index(name = "idx_start_time", columnList = "start_time"),
        Index(name = "idx_end_time", columnList = "end_time"),
        Index(name = "idx_created_at", columnList = "created_at")
    ]
)
data class MaintenanceBanner(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 2000)
    var message: String,

    @Column(nullable = false, name = "start_time")
    var startTime: Instant,

    @Column(nullable = false, name = "end_time")
    var endTime: Instant,

    @Column(nullable = false, name = "created_at")
    val createdAt: Instant = Instant.now(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = true)  // Nullable to preserve history on user deletion
    var createdBy: User? = null
) {
    /**
     * Check if this banner is currently active based on the current time.
     *
     * @return true if current time is between startTime and endTime (inclusive)
     */
    fun isActive(): Boolean {
        val now = Instant.now()
        return now >= startTime && now <= endTime
    }

    /**
     * Get the current status of this banner based on current time.
     *
     * @return BannerStatus.UPCOMING if not yet active, ACTIVE if currently active, EXPIRED if past
     */
    fun getStatus(): BannerStatus {
        val now = Instant.now()
        return when {
            startTime > now -> BannerStatus.UPCOMING
            endTime < now -> BannerStatus.EXPIRED
            else -> BannerStatus.ACTIVE
        }
    }
}
