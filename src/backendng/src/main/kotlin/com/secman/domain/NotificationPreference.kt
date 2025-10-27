package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * Stores user preferences for notification delivery
 * Feature 035: Outdated Asset Notification System
 */
@Entity
@Table(name = "notification_preference")
data class NotificationPreference(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false, unique = true)
    val userId: Long,

    @Column(name = "enable_new_vuln_notifications", nullable = false)
    val enableNewVulnNotifications: Boolean = false,

    @Column(name = "last_vuln_notification_sent_at")
    val lastVulnNotificationSentAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}
