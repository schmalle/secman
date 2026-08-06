package com.secman.service

import com.secman.domain.TelegramConfig
import com.secman.repository.TelegramConfigRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/**
 * Accessor for the single workspace-level [TelegramConfig] row.
 *
 * Same singleton-row rule as [SlackConfigService]: reads take the lowest-id row, writes
 * update it (creating it on first save), so a stray second row can never become the
 * effective config for some callers but not others.
 */
@Singleton
open class TelegramConfigService(
    private val telegramConfigRepository: TelegramConfigRepository
) {
    private val log = LoggerFactory.getLogger(TelegramConfigService::class.java)

    /** The effective config, or null when Telegram has never been configured. */
    open fun find(): TelegramConfig? =
        telegramConfigRepository.findAll().minByOrNull { it.id ?: Long.MAX_VALUE }

    /**
     * Update (or create) the workspace config.
     *
     * @param botToken null means "leave the stored token unchanged"; blank means "clear it".
     */
    @Transactional
    open fun save(enabled: Boolean, botToken: String?): TelegramConfig {
        val existing = find()

        return if (existing == null) {
            log.info("Creating Telegram workspace configuration (enabled={})", enabled)
            telegramConfigRepository.save(
                TelegramConfig(enabled = enabled, botToken = botToken?.takeIf { it.isNotBlank() })
            )
        } else {
            existing.enabled = enabled
            if (botToken != null) {
                existing.botToken = botToken.takeIf { it.isNotBlank() }
            }
            log.info("Updating Telegram workspace configuration (enabled={})", enabled)
            telegramConfigRepository.update(existing)
        }
    }
}
