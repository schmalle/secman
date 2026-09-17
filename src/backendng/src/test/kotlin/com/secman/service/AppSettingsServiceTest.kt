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
import java.util.Optional

class AppSettingsServiceTest {
    private val repository: AppSettingsRepository = mockk()
    private val config = AiRiskAssessmentConfig(model = "openai/gpt-4.1")
    private val catchAllSafety = mockk<CatchAllWorkgroupSafetyService>(relaxed = true)
    private val service = AppSettingsService(repository, config, catchAllSafety)

    @Test
    fun `get settings returns persisted ai model`() {
        every { repository.findFirstSettings() } returns Optional.of(
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
        every { repository.findFirstSettings() } returns Optional.of(existing)
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
    fun `update settings persists and enforces catch-all threshold`() {
        val existing = AppSettings(id = 1L, baseUrl = "https://secman.example.com")
        every { repository.findFirstSettings() } returns Optional.of(existing)
        every { repository.update(any()) } answers { arg(0) }

        val updated = service.updateSettings(
            baseUrl = "https://secman.example.com",
            updatedBy = "admin",
            catchAllWorkgroupUserThreshold = 125
        )

        assertEquals(125, updated.catchAllWorkgroupUserThreshold)
        verify(exactly = 1) { catchAllSafety.validateThreshold(125) }
        verify(exactly = 1) { catchAllSafety.enforceAll(125, "admin") }
    }

    @Test
    fun `legacy settings update preserves configured catch-all threshold`() {
        val existing = AppSettings(
            id = 1L,
            baseUrl = "https://secman.example.com",
            catchAllWorkgroupUserThreshold = 275
        )
        every { repository.findFirstSettings() } returns Optional.of(existing)
        every { repository.update(any()) } answers { arg(0) }

        val updated = service.updateSettings(
            baseUrl = "https://secman.example.com",
            updatedBy = "admin"
        )

        assertEquals(275, updated.catchAllWorkgroupUserThreshold)
        verify(exactly = 1) { catchAllSafety.enforceAll(275, "admin") }
    }

    @Test
    fun `update settings rejects blank ai model`() {
        every { repository.findFirstSettings() } returns Optional.of(AppSettings(id = 1L, baseUrl = "https://secman.example.com"))

        assertThrows(IllegalArgumentException::class.java) {
            service.updateSettings(
                baseUrl = "https://secman.example.com",
                updatedBy = "admin",
                aiRiskAssessmentModel = "   "
            )
        }
    }
}
