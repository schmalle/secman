package com.secman.service

import com.secman.domain.AccountOnboardingMode
import com.secman.domain.AccountOnboardingSettings
import com.secman.dto.AccountOnboardingSettingsDto
import com.secman.repository.AccountOnboardingSettingsRepository
import com.secman.repository.UseCaseRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

class AccountOnboardingSettingsServiceTest {
    private val repository = mockk<AccountOnboardingSettingsRepository>()
    private val useCases = mockk<UseCaseRepository>()
    private val service = AccountOnboardingSettingsService(repository, useCases, EmailTemplateRenderer())

    @Test
    fun `an unconfigured installation welcomes owners without an assessment`() {
        every { repository.findBySingletonKey(1) } returns Optional.empty()
        assertThat(service.get().mode).isEqualTo(AccountOnboardingMode.WELCOME_ONLY)
    }

    @Test
    fun `saving rich text strips active content and unsafe links`() {
        every { repository.findBySingletonKey(1) } returns Optional.empty()
        every { repository.save(any<AccountOnboardingSettings>()) } answers { firstArg() }
        val saved = service.save(AccountOnboardingSettingsDto(welcomeBodyHtml =
            "<p onclick='evil()'>Welcome</p><script>evil()</script><a href='javascript:evil()'>link</a>"))
        assertThat(saved.welcomeBodyHtml).contains("Welcome").doesNotContain("onclick", "script", "javascript:")
        verify(exactly = 1) { repository.save(any<AccountOnboardingSettings>()) }
    }

    @Test
    fun `direct assessment requires an existing use case`() {
        every { useCases.findByNameIgnoreCase("missing") } returns Optional.empty()
        assertThatThrownBy { service.save(AccountOnboardingSettingsDto(
            mode = AccountOnboardingMode.DIRECT, riskAssessmentUseCase = "missing")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { repository.save(any<AccountOnboardingSettings>()) }
    }

    @Test
    fun `mail subjects reject header injection`() {
        assertThatThrownBy { service.save(AccountOnboardingSettingsDto(welcomeSubject = "Hi\r\nBcc: unwanted@example.test")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `simulated mail retains marker and escapes placeholder values`() {
        every { repository.findBySingletonKey(1) } returns Optional.of(AccountOnboardingSettings(
            welcomeBodyHtml = "<p>{ownerEmail}</p><p>\${notExecuted}</p>"))
        val mail = service.renderWelcome(mapOf("ownerEmail" to "<img src=x onerror=evil()>", "simulatedBy" to "admin"), true)
        assertThat(mail.subject).startsWith("[SIMULATION]")
        assertThat(mail.html).contains("SIMULATION", "admin", "&lt;img", "\${notExecuted}").doesNotContain("<img")
    }
}
