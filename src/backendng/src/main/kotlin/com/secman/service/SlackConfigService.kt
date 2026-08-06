package com.secman.service

import com.secman.domain.SlackConfig
import com.secman.repository.SlackConfigRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/**
 * Accessor for the single workspace-level [SlackConfig] row.
 *
 * The table is treated as a singleton: reads take the lowest-id row, writes update it
 * (creating it on first save). Keeping that rule in one place stops a second row —
 * created by a race or a stray insert — from silently becoming the effective config
 * for some callers but not others.
 */
@Singleton
open class SlackConfigService(
    private val slackConfigRepository: SlackConfigRepository
) {
    private val log = LoggerFactory.getLogger(SlackConfigService::class.java)

    /** The effective config, or null when Slack has never been configured. */
    open fun find(): SlackConfig? =
        slackConfigRepository.findAll().minByOrNull { it.id ?: Long.MAX_VALUE }

    /**
     * Update (or create) the workspace config.
     *
     * @param botToken null means "leave the stored token unchanged"; blank means "clear it".
     */
    @Transactional
    open fun save(enabled: Boolean, botToken: String?, defaultChannel: String?): SlackConfig {
        val existing = find()
        val normalizedChannel = defaultChannel?.trim()?.takeIf { it.isNotEmpty() }

        return if (existing == null) {
            val created = SlackConfig(
                enabled = enabled,
                botToken = botToken?.takeIf { it.isNotBlank() },
                defaultChannel = normalizedChannel
            )
            log.info("Creating Slack workspace configuration (enabled={})", enabled)
            slackConfigRepository.save(created)
        } else {
            existing.enabled = enabled
            existing.defaultChannel = normalizedChannel
            if (botToken != null) {
                existing.botToken = botToken.takeIf { it.isNotBlank() }
            }
            log.info("Updating Slack workspace configuration (enabled={})", enabled)
            slackConfigRepository.update(existing)
        }
    }
}
