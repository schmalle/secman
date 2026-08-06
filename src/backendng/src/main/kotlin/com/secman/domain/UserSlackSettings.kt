package com.secman.domain

import com.secman.util.EncryptedStringConverter
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/**
 * Per-user Slack delivery settings.
 *
 * "Where do my Slack notifications go" lives here; "which events do I want over Slack"
 * lives in [UserNotificationSubscription] keyed by [NotificationChannel.SLACK]. Splitting
 * them keeps the event catalogue open-ended — a new [NotificationEventType] needs no
 * schema change.
 *
 * Destination resolution (first match wins), see `ChatNotificationService`:
 *  1. personal incoming webhook ([webhookUrl])
 *  2. personal channel ([channel]) via the workspace bot token
 *  3. the workspace default channel via the workspace bot token
 */
@Entity
@Table(name = "user_slack_settings")
@Serdeable
data class UserSlackSettings(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", nullable = false, unique = true)
    var userId: Long,

    /** Master switch for this user. Off means no Slack delivery regardless of subscriptions. */
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false,

    /**
     * Personal Slack incoming webhook URL. Encrypted at rest and never returned by the
     * API — it is a bearer credential for posting into the user's channel.
     *
     * Validated against a fixed host prefix before it is ever fetched
     * (`SlackClient.validateWebhookUrl`); the server makes the outbound request, so an
     * unvalidated value here would be a server-side request forgery primitive.
     */
    @Column(name = "webhook_url", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter::class)
    var webhookUrl: String? = null,

    /** Channel or member ID (`#alerts`, `C012AB3CD`, `U012AB3CD`) for bot-token delivery. */
    @Column(name = "channel", length = 100)
    var channel: String? = null,

    @Column(name = "last_notified_at")
    var lastNotifiedAt: LocalDateTime? = null,

    /** One of [ChatDeliveryStatus] — outcome of the most recent delivery attempt. */
    @Column(name = "last_delivery_status", length = 20)
    var lastDeliveryStatus: String? = null,

    @Column(name = "last_delivery_error", length = 500)
    var lastDeliveryError: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {
    companion object {
        /**
         * Returned instead of a stored webhook URL, and accepted back verbatim on update
         * to mean "keep the stored URL". An empty string clears it.
         */
        const val WEBHOOK_MASK = "***HIDDEN***"
    }
}
