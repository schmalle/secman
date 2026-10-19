package com.secman.service.aibuddy

import com.secman.domain.TranslationConfig
import com.secman.repository.TranslationConfigRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

class AiBuddySettingsTest {
    private fun record() = TranslationConfig(apiKey = "fixture", modelName = "vendor/model", maxTokens = 8000,
        isActive = true, aiBuddyEnabled = true, aiBuddySingleInstance = true, aiBuddyProviders = "vendor, vendor")

    @Test fun `active admin record supplies key model tokens providers and budgets without env fallback`() {
        val repo = mockk<TranslationConfigRepository>()
        val active = record().apply { aiBuddyDailyBudgetUsd = 3.0 }
        every { repo.findActiveConfig() } returns Optional.of(active)
        val selected = AiBuddySettingsService(repo).current()
        assertThat(selected.apiKey).isEqualTo(active.apiKey)
        assertThat(selected.config.model).isEqualTo(active.modelName)
        assertThat(selected.config.maxOutputTokens).isEqualTo(4096)
        assertThat(selected.config.providers).containsExactly("vendor")
        assertThat(selected.config.dailyBudgetUsd).isEqualTo(3.0)
        assertThat(selected.unavailableReason()).isNull()
        every { repo.findActiveConfig() } returns Optional.empty()
        assertThat(AiBuddySettingsService(repo).current().unavailableReason()).isEqualTo("FEATURE_DISABLED")
        every { repo.findActiveConfig() } throws IllegalStateException("private database detail")
        assertThat(assertThrows<AiBuddyFailure> { AiBuddySettingsService(repo).current() }.code).isEqualTo("NOT_CONFIGURED")
    }

    @Test fun `shared settings can be saved before beta generation is ready`() {
        for (draft in listOf(record().copy(modelName = "~vendor/latest"), record().copy(baseUrl = "https://example.invalid"),
            record().copy(aiBuddyProviders = ""), record().copy(aiBuddySingleInstance = false), record().copy(maxTokens = 100))) {
            assertThat(AiBuddySettingsService.valid(draft)).isTrue()
            assertThat(AiBuddySettingsService.from(draft).unavailableReason()).isNotNull()
        }
    }

    @Test fun `enabled configuration reports the specific missing provider without disabling the feature`() {
        val selected = AiBuddySettingsService.from(record().copy(aiBuddyProviders = ""))
        assertThat(selected.config.enabled).isTrue()
        assertThat(selected.unavailableReason()).isEqualTo("APPROVED_PROVIDER_REQUIRED")
        assertThat(AiBuddySettingsService.from(record().copy(aiBuddySingleInstance = false)).unavailableReason())
            .isEqualTo("SINGLE_INSTANCE_REQUIRED")
        assertThat(AiBuddySettingsService.from(record()).unavailableReason()).isNull()
    }

    @Test fun `persisted settings still reject malformed values and fingerprint changes`() {
        assertThat(AiBuddySettingsService.valid(record().copy(aiBuddyDailyBudgetUsd = Double.NaN))).isFalse()
        assertThat(AiBuddySettingsService.valid(record().copy(aiBuddyProviders = "x".repeat(513)))).isFalse()
        assertThat(AiBuddySettingsService.valid(record().copy(maxTokens = 99))).isFalse()
        assertThat(AiBuddySettingsService.from(record().copy(aiBuddyProviders = "other")).fingerprint)
            .isNotEqualTo(AiBuddySettingsService.from(record()).fingerprint)
    }
}
