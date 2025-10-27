package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * Tracks the reminder level progression for each outdated asset
 * Feature 035: Outdated Asset Notification System
 */
@Entity
@Table(
    name = "asset_reminder_state",
    indexes = [
        Index(name = "idx_reminder_state_last_sent", columnList = "last_sent_at"),
        Index(name = "idx_reminder_state_outdated_since", columnList = "outdated_since")
    ]
)
data class AssetReminderState(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "asset_id", nullable = false, unique = true)
    val assetId: Long,

    @Column(name = "level", nullable = false)
    val level: Int, // 1 or 2

    @Column(name = "last_sent_at", nullable = false)
    var lastSentAt: Instant,

    @Column(name = "outdated_since", nullable = false)
    val outdatedSince: Instant,

    @Column(name = "last_checked_at", nullable = false)
    var lastCheckedAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
) {
    init {
        require(level in 1..2) { "Reminder level must be 1 or 2" }
    }
}
