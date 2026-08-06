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
 * Per-user Telegram delivery settings.
 *
 * "Where do my Telegram notifications go" lives here; "which events do I want over
 * Telegram" lives in [UserNotificationSubscription] keyed by
 * [NotificationChannel.TELEGRAM].
 *
 * Destination resolution needs a token *and* a chat id:
 *  - [chatId] is always the user's own — Telegram addresses a conversation, not a person.
 *  - the token is [botToken] when the user runs their own bot, otherwise the workspace
 *    bot from [TelegramConfig].
 */
@Entity
@Table(name = "user_telegram_settings")
@Serdeable
data class UserTelegramSettings(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", nullable = false, unique = true)
    var userId: Long,

    /** Master switch for this user. Off means no Telegram delivery regardless of subscriptions. */
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false,

    /**
     * Numeric chat id (negative for groups) or `@publicchannel`. The user gets theirs by
     * messaging the bot; validated by `TelegramClient.validateChatId` before storage.
     */
    @Column(name = "chat_id", length = 64)
    var chatId: String? = null,

    /**
     * Optional personal bot token, for a user who runs their own bot instead of using the
     * workspace one. Encrypted at rest and never returned by the API.
     */
    @Column(name = "bot_token", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter::class)
    var botToken: String? = null,

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
        /** See [UserSlackSettings.WEBHOOK_MASK] — same keep-the-stored-value convention. */
        const val TOKEN_MASK = "***HIDDEN***"
    }
}
