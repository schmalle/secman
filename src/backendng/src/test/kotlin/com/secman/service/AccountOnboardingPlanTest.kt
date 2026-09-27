package com.secman.service

import com.secman.domain.AccountOnboardingMode
import com.secman.dto.AccountOnboardingSettingsDto
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AccountOnboardingPlanTest {
    private val settings = mockk<AccountOnboardingSettingsService>()
    private val service = AccountOnboardingService(
        awsAccountRiskAssessmentService = mockk(), ruleMatcher = mockk(), questionRepository = mockk(),
        ruleRepository = mockk(), inviteRepository = mockk(), trackingRepository = mockk(),
        riskAssessmentRepository = mockk(), userRepository = mockk(), emailService = mockk(),
        ownerMailDeliveryService = mockk(), templateRenderer = EmailTemplateRenderer(),
        releaseRequirementScopeService = mockk(), objectMapper = mockk(), appConfig = mockk(),
        selfProvider = mockk(), maxAccountsPerRun = 200, defaultExpiryDays = 14,
        welcomeTemplate = "account-welcome", questionnaireTemplate = "account-onboarding-questionnaire",
        reminderTemplate = "account-onboarding-reminder", reminderDaysBefore = 3, settings = settings
    )

    @Test
    fun `notify-only selects preferred welcome or saved direct action`() {
        every { settings.get() } returns AccountOnboardingSettingsDto()
        val welcome = service.planFrom(null, false, null, null, null, null, useDefaultSettings = true)!!
        assertThat(welcome.mode).isEqualTo(AccountOnboardingMode.WELCOME_ONLY)
        assertThat(welcome.sendWelcomeEmail).isTrue()
        every { settings.get() } returns AccountOnboardingSettingsDto(
            mode = AccountOnboardingMode.DIRECT, riskAssessmentUseCase = "Cloud", riskAssessmentDeadlineDays = 21)
        val direct = service.planFrom(null, false, null, null, null, null, useDefaultSettings = true)!!
        assertThat(direct.mode).isEqualTo(AccountOnboardingMode.DIRECT)
        assertThat(direct.useCaseName).isEqualTo("Cloud")
        assertThat(direct.deadlineDays).isEqualTo(21)
        assertThat(direct.sendWelcomeEmail).isFalse()
    }

    @Test
    fun `explicit and legacy requests override saved policy and plain import stays inert`() {
        val explicit = service.planFrom(AccountOnboardingMode.GUIDED, false, null, null, null, null, useDefaultSettings = true)!!
        assertThat(explicit.mode).isEqualTo(AccountOnboardingMode.GUIDED)
        val legacy = service.planFrom(null, true, null, "Explicit", 10, null, useDefaultSettings = true)!!
        assertThat(legacy.mode).isEqualTo(AccountOnboardingMode.DIRECT)
        assertThat(legacy.useCaseName).isEqualTo("Explicit")
        assertThat(legacy.sendWelcomeEmail).isFalse()
        assertThat(service.planFrom(null, false, null, null, null, null)).isNull()
    }
}
