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
 * Workspace-level Telegram configuration (ADMIN managed).
 *
 * Holds the shared bot token users' chat IDs are addressed through. Optional: a user who
 * runs their own bot can store a personal token on [UserTelegramSettings] instead.
 *
 * Exactly one row is meaningful — [com.secman.service.TelegramConfigService] always
 * reads/writes the single lowest-id row, mirroring [SlackConfig].
 */
@Entity
@Table(name = "telegram_config")
@Serdeable
data class TelegramConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /**
     * Telegram bot token from @BotFather. Encrypted at rest, never returned by the API —
     * the controller reports only whether one is configured.
     */
    @Column(name = "bot_token", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter::class)
    var botToken: String? = null,

    /**
     * Master switch for shared-bot delivery. Personal bot tokens are unaffected by it, so
     * turning this off degrades to personal-bot-only rather than disabling Telegram.
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
        /** See [SlackConfig.TOKEN_MASK] — same keep-the-stored-value convention. */
        const val TOKEN_MASK = "***HIDDEN***"
    }

    /** True when shared-bot delivery is usable (switched on and a token is stored). */
    fun isBotDeliveryUsable(): Boolean = enabled && !botToken.isNullOrBlank()
}
