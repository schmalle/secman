package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/**
 * Entity for storing admin notification settings
 * Feature: 027-admin-user-notifications
 *
 * Stores configuration for email notifications sent to ADMIN users
 * when new users are created (via OAuth or manual creation)
 */
@Entity
@Table(name = "admin_notification_settings")
@Serdeable
data class AdminNotificationSettings(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * Enable/disable email notifications for new user registrations
     * Default: true (opt-out model per spec)
     */
    @Column(nullable = false, name = "notifications_enabled")
    var notificationsEnabled: Boolean = true,

    /**
     * Sender email address used in From: field of notification emails
     * Default: noreply@secman.local (can be customized by ADMIN)
     */
    @Column(nullable = false, length = 255, name = "sender_email")
    var senderEmail: String = "noreply@secman.local",

    /**
     * Username of ADMIN who last updated these settings
     */
    @Column(nullable = true, length = 100, name = "updated_by")
    var updatedBy: String? = null,

    @CreationTimestamp
    @Column(nullable = false, updatable = false, name = "created_at")
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(nullable = false, name = "updated_at")
    var updatedAt: LocalDateTime? = null
) {
    companion object {
        /**
         * Create default settings (enabled with default sender)
         */
        fun createDefault(): AdminNotificationSettings {
            return AdminNotificationSettings(
                notificationsEnabled = true,
                senderEmail = "noreply@secman.local",
                updatedBy = "system"
            )
        }
    }

    /**
     * Update settings and track who made the change
     */
    fun update(enabled: Boolean, senderEmail: String, updatedByUsername: String): AdminNotificationSettings {
        return this.copy(
            notificationsEnabled = enabled,
            senderEmail = senderEmail,
            updatedBy = updatedByUsername
        )
    }

    /**
     * Validate sender email format
     */
    fun validateSenderEmail(): List<String> {
        val errors = mutableListOf<String>()

        if (senderEmail.isBlank()) {
            errors.add("Sender email cannot be empty")
        } else if (!senderEmail.matches(Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\$"))) {
            errors.add("Invalid sender email format: $senderEmail")
        }

        return errors
    }
}
