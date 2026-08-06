package com.secman.service

import com.secman.config.AiRiskAssessmentConfig
import com.secman.domain.AppSettings
import com.secman.repository.AppSettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppSettingsServiceTest {
    private val repository: AppSettingsRepository = mockk()
    private val config = AiRiskAssessmentConfig(model = "openai/gpt-4.1")
    private val service = AppSettingsService(repository, config)

    @Test
    fun `get settings returns persisted ai model`() {
        every { repository.findAll() } returns listOf(
            AppSettings(
                id = 1L,
                baseUrl = "https://secman.example.com",
                aiRiskAssessmentEnabled = true,
                aiRiskAssessmentModel = "anthropic/claude-sonnet-4.6:online",
                updatedBy = "admin"
            )
        )

        val dto = service.getSettings()

        assertEquals("anthropic/claude-sonnet-4.6:online", dto.aiRiskAssessmentModel)
    }

    @Test
    fun `update settings persists ai model`() {
        val existing = AppSettings(id = 1L, baseUrl = "https://secman.example.com", updatedBy = "system")
        every { repository.findAll() } returns listOf(existing)
        val captured = slot<AppSettings>()
        every { repository.update(capture(captured)) } answers { captured.captured }

        val updated = service.updateSettings(
            baseUrl = "https://secman.example.com",
            updatedBy = "admin",
            aiRiskAssessmentEnabled = true,
            aiRiskAssessmentModel = "openai/gpt-4.1"
        )

        assertEquals("openai/gpt-4.1", updated.aiRiskAssessmentModel)
        assertEquals("openai/gpt-4.1", captured.captured.aiRiskAssessmentModel)
        verify(exactly = 1) { repository.update(any()) }
    }

    @Test
    fun `update settings rejects blank ai model`() {
        every { repository.findAll() } returns listOf(AppSettings(id = 1L, baseUrl = "https://secman.example.com"))

        assertThrows(IllegalArgumentException::class.java) {
            service.updateSettings(
                baseUrl = "https://secman.example.com",
                updatedBy = "admin",
                aiRiskAssessmentModel = "   "
            )
        }
    }
}
