package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * Audit trail for all notifications sent
 * Feature 035: Outdated Asset Notification System
 */
@Entity
@Table(
    name = "notification_log",
    indexes = [
        Index(name = "idx_notif_log_asset", columnList = "asset_id"),
        Index(name = "idx_notif_log_sent_at", columnList = "sent_at"),
        Index(name = "idx_notif_log_owner_email", columnList = "owner_email"),
        Index(name = "idx_notif_log_type_sent_at", columnList = "notification_type, sent_at")
    ]
)
data class NotificationLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "asset_id")
    val assetId: Long?, // Nullable because asset may be deleted after notification

    @Column(name = "asset_name", nullable = false, length = 255)
    val assetName: String,

    @Column(name = "owner_email", nullable = false, length = 255)
    val ownerEmail: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 50)
    val notificationType: NotificationType,

    @Column(name = "sent_at", nullable = false)
    val sentAt: Instant = Instant.now(),

    @Column(name = "status", nullable = false, length = 20)
    val status: String, // SENT, FAILED, PENDING

    @Column(name = "error_message", length = 1024)
    val errorMessage: String? = null
) {
    init {
        require(assetName.isNotBlank()) { "Asset name must not be blank" }
        require(ownerEmail.isNotBlank()) { "Owner email must not be blank" }
        require(status in listOf("SENT", "FAILED", "PENDING")) { "Status must be SENT, FAILED, or PENDING" }
    }
}
