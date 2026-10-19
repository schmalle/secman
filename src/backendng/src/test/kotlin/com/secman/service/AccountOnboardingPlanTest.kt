package com.secman.service

import com.secman.domain.AccountOnboardingMode
import com.secman.dto.AccountOnboardingSettingsDto
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AccountOnboardingPlanTest {
    private val ownerMail = mockk<OwnerMailDeliveryService>()
    private val settings = mockk<AccountOnboardingSettingsService>()
    private val service = AccountOnboardingService(
        awsAccountRiskAssessmentService = mockk(), ruleMatcher = mockk(), questionRepository = mockk(),
        ruleRepository = mockk(), inviteRepository = mockk(), trackingRepository = mockk(),
        riskAssessmentRepository = mockk(), userRepository = mockk(), emailService = mockk(),
        ownerMailDeliveryService = ownerMail, templateRenderer = EmailTemplateRenderer(),
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

    @Test
    fun `welcome mail groups normalized owners across new accounts in real and dry imports`() {
        val ids = listOf("111111111111", "222222222222")
        val accounts = listOf(
            com.secman.dto.NewAccountImportInfo(ids[0], listOf(" OWNER@example.test ", "owner@example.test")),
            com.secman.dto.NewAccountImportInfo(ids[1], listOf("Owner@Example.test", "other@example.test")))
        val plan = service.planFrom(AccountOnboardingMode.WELCOME_ONLY, false, null, null, null, null)!!
        for (dryRun in listOf(true, false)) {
            io.mockk.clearMocks(ownerMail)
            val status = if (dryRun) "WOULD_SEND" else "SENT"
            every { ownerMail.welcome(ids, "owner@example.test", true, dryRun, null) } returns
                com.secman.dto.OwnerMailDelivery(true, status, if (dryRun) null else 42, accountIds = ids)
            every { ownerMail.welcome(listOf(ids[1]), "other@example.test", true, dryRun, null) } returns
                com.secman.dto.OwnerMailDelivery(true, status, if (dryRun) null else 43, accountIds = listOf(ids[1]))
            val result = service.onboardNewAccounts(accounts, plan, null, dryRun)
            assertThat(result.onboarding).hasSize(3)
            val ownerResults = result.onboarding.filter { it.ownerEmail == "owner@example.test" }
            assertThat(ownerResults.map { it.welcomeEmail }.distinct()).hasSize(1)
            assertThat(ownerResults.first().welcomeEmail.accountIds).containsExactlyElementsOf(ids)
            io.mockk.verify(exactly = 1) { ownerMail.welcome(ids, "owner@example.test", true, dryRun, null) }
            io.mockk.verify(exactly = 1) { ownerMail.welcome(listOf(ids[1]), "other@example.test", true, dryRun, null) }
        }
    }
}
