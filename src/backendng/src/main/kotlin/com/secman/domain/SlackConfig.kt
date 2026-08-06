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
 * Workspace-level Slack configuration (ADMIN managed).
 *
 * Holds the Slack bot token used for `chat.postMessage`, which is what lets a user
 * name a channel instead of pasting a personal incoming webhook. It is optional:
 * with no active config, users can still receive Slack notifications through their
 * own incoming webhook URL. See [UserSlackSettings].
 *
 * Exactly one row is meaningful — [SlackConfigService] always reads/writes the
 * single lowest-id row, mirroring how the rest of the platform treats singleton
 * configuration.
 */
@Entity
@Table(name = "slack_config")
@Serdeable
data class SlackConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /**
     * Slack bot token (`xoxb-…`). Encrypted at rest, never returned by the API —
     * the controller reports only whether one is configured.
     */
    @Column(name = "bot_token", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter::class)
    var botToken: String? = null,

    /**
     * Channel used when a subscriber has neither a personal webhook nor a personal
     * channel configured. Optional — without it such a user simply gets nothing.
     */
    @Column(name = "default_channel", length = 100)
    var defaultChannel: String? = null,

    /**
     * Master switch for bot-token delivery. Personal webhooks are unaffected by it,
     * so turning this off degrades to webhook-only rather than disabling Slack.
     */
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {
    companion object {
        /**
         * Sent to the client in place of a stored token, and accepted back verbatim on
         * update to mean "leave the stored token alone". Same convention as
         * [EmailConfig.PASSWORD_MASK].
         */
        const val TOKEN_MASK = "***HIDDEN***"
    }

    /** True when bot-token delivery is usable (switched on and a token is stored). */
    fun isBotDeliveryUsable(): Boolean = enabled && !botToken.isNullOrBlank()
}
