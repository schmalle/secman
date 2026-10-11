package com.secman.service.aibuddy

import com.secman.config.AiBuddyConfig
import com.secman.domain.TranslationConfig
import com.secman.repository.TranslationConfigRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

class AiBuddySettings(val config: AiBuddyConfig, val apiKey: String, val baseUrl: String, val fingerprint: String) {
    fun unavailableReason(): String? = config.unavailableReason(apiKey)
        ?: if (baseUrl.trimEnd('/') != "https://openrouter.ai/api/v1" ||
            !Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(config.model)) "MODEL_UNSUPPORTED" else null
}

/** One active admin record supplies the credential, model and beta controls; no env fallback. */
@Singleton
open class AiBuddySettingsService(private val configs: TranslationConfigRepository) {
    open fun current(): AiBuddySettings = try {
        from(configs.findActiveConfig().orElse(null))
    } catch (e: Exception) {
        LoggerFactory.getLogger(javaClass).warn("OpenRouter settings outcome=unavailable")
        throw AiBuddyFailure("NOT_CONFIGURED")
    }

    companion object {
        fun from(record: TranslationConfig?): AiBuddySettings {
            val config = AiBuddyConfig()
            if (record != null) {
                config.enabled = record.aiBuddyEnabled
                config.singleInstance = record.aiBuddySingleInstance
                config.model = record.modelName.trim()
                config.maxOutputTokens = record.maxTokens.coerceAtMost(4096)
                config.providers = record.aiBuddyProviders.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                config.maxCostPerJobUsd = record.aiBuddyMaxCostPerJobUsd
                config.dailyBudgetUsd = record.aiBuddyDailyBudgetUsd
                config.maxInputPricePerMillion = record.aiBuddyMaxInputPricePerMillion
                config.maxOutputPricePerMillion = record.aiBuddyMaxOutputPricePerMillion
                config.microsoftEvidenceEnabled = record.aiBuddyMicrosoftEvidenceEnabled
                config.crowdStrikeEvidenceEnabled = record.aiBuddyCrowdStrikeEvidenceEnabled
            }
            val facts = listOf(record?.id, record?.updatedAt, config.enabled, config.singleInstance, config.model,
                config.providers.sorted(), config.maxOutputTokens, config.maxCostPerJobUsd, config.dailyBudgetUsd,
                config.maxInputPricePerMillion, config.maxOutputPricePerMillion, config.microsoftEvidenceEnabled,
                config.crowdStrikeEvidenceEnabled, record?.baseUrl, "prompt-001")
            return AiBuddySettings(config, record?.apiKey.orEmpty(), record?.baseUrl.orEmpty(), digest(facts.toString().toByteArray()))
        }

        // Saving a shared record is independent of beta readiness; current() gates every generation.
        fun valid(record: TranslationConfig): Boolean = record.isValid() && record.aiBuddyProviders.length <= 512 &&
            listOf(record.aiBuddyMaxCostPerJobUsd, record.aiBuddyDailyBudgetUsd, record.aiBuddyMaxInputPricePerMillion,
                record.aiBuddyMaxOutputPricePerMillion).all { it.isFinite() && it > 0 && it <= 1_000_000 }
    }
}
