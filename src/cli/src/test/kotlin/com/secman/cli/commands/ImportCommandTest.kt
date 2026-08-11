package com.secman.cli.commands

import com.secman.cli.service.UserMappingCliService
import io.micronaut.context.ApplicationContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportCommandTest {

    private lateinit var context: ApplicationContext
    private lateinit var service: UserMappingCliService

    @BeforeAll
    fun setup() {
        // The CLI Micronaut context wires UserMappingCliService's deps
        // (UserMappingValidator, CliJavaHttpClientFactory). validateNotifyOptions()
        // never calls the service, so a real wired instance is fine.
        context = ApplicationContext.builder().environments("cli").start()
        service = context.getBean(UserMappingCliService::class.java)
    }

    @AfterAll
    fun teardown() {
        context.close()
    }

    private fun cmd(createnotify: Boolean, notifyAddress: String?): ImportCommand {
        val c = ImportCommand(service)
        c.createnotify = createnotify
        c.notifyAddress = notifyAddress
        return c
    }

    @Test
    fun `createnotify without notify-address is rejected`() {
        assertThat(cmd(createnotify = true, notifyAddress = null).validateNotifyOptions()).isNotNull()
    }

    @Test
    fun `createnotify with blank notify-address is rejected`() {
        assertThat(cmd(createnotify = true, notifyAddress = "   ").validateNotifyOptions()).isNotNull()
    }

    @Test
    fun `createnotify with valid notify-address passes`() {
        assertThat(cmd(createnotify = true, notifyAddress = "ops@corp.com").validateNotifyOptions()).isNull()
    }

    @Test
    fun `no createnotify passes regardless of notify-address`() {
        assertThat(cmd(createnotify = false, notifyAddress = null).validateNotifyOptions()).isNull()
        assertThat(cmd(createnotify = false, notifyAddress = "ops@corp.com").validateNotifyOptions()).isNull()
    }

    // --- --start-risk-assessment option validation ---

    private fun riskCmd(start: Boolean, useCase: String?, deadlineDays: Int = 7): ImportCommand {
        val c = ImportCommand(service)
        c.startRiskAssessment = start
        c.riskUseCase = useCase
        c.riskDeadlineDays = deadlineDays
        return c
    }

    @Test
    fun `start-risk-assessment without risk-usecase is rejected`() {
        assertThat(riskCmd(start = true, useCase = null).validateRiskAssessmentOptions()).isNotNull()
        assertThat(riskCmd(start = true, useCase = "   ").validateRiskAssessmentOptions()).isNotNull()
    }

    @Test
    fun `start-risk-assessment with deadline below 1 day is rejected`() {
        assertThat(riskCmd(start = true, useCase = "Cloud Onboarding", deadlineDays = 0).validateRiskAssessmentOptions()).isNotNull()
        assertThat(riskCmd(start = true, useCase = "Cloud Onboarding", deadlineDays = -3).validateRiskAssessmentOptions()).isNotNull()
    }

    @Test
    fun `start-risk-assessment with usecase and default deadline passes`() {
        assertThat(riskCmd(start = true, useCase = "Cloud Onboarding").validateRiskAssessmentOptions()).isNull()
        assertThat(riskCmd(start = true, useCase = "Cloud Onboarding", deadlineDays = 14).validateRiskAssessmentOptions()).isNull()
    }

    @Test
    fun `no start-risk-assessment passes regardless of other risk options`() {
        assertThat(riskCmd(start = false, useCase = null).validateRiskAssessmentOptions()).isNull()
        assertThat(riskCmd(start = false, useCase = "Cloud Onboarding", deadlineDays = 0).validateRiskAssessmentOptions()).isNull()
    }

    @Test
    fun `start-risk-assessment with a deadline beyond the cap is rejected`() {
        val max = UserMappingCliService.MAX_RISK_DEADLINE_DAYS

        assertThat(riskCmd(start = true, useCase = "Cloud Onboarding", deadlineDays = max + 1)
            .validateRiskAssessmentOptions()).contains("at most $max")
        assertThat(riskCmd(start = true, useCase = "Cloud Onboarding", deadlineDays = Int.MAX_VALUE)
            .validateRiskAssessmentOptions()).isNotNull()
        // Inclusive boundary.
        assertThat(riskCmd(start = true, useCase = "Cloud Onboarding", deadlineDays = max)
            .validateRiskAssessmentOptions()).isNull()
    }

    /**
     * The cap must be the same number the backend enforces
     * (`AwsAccountRiskAssessmentService.MAX_DEADLINE_DAYS`). The CLI is a separate Gradle
     * module and cannot reference that constant, so this pins the duplicate.
     */
    @Test
    fun `the CLI deadline cap matches the backend cap`() {
        assertThat(UserMappingCliService.MAX_RISK_DEADLINE_DAYS).isEqualTo(3650)
    }

    // --- Onboarding options ---------------------------------------------------

    private fun onboardingCmd(
        mode: ImportCommand.OnboardingMode? = null,
        start: Boolean = false,
        useCase: String? = null,
        expiryDays: Int = 14,
        welcomeEmail: Boolean? = null,
        deadlineDays: Int = 7
    ): ImportCommand {
        val c = ImportCommand(service)
        c.onboardingMode = mode
        c.startRiskAssessment = start
        c.riskUseCase = useCase
        c.questionnaireExpiryDays = expiryDays
        c.welcomeEmail = welcomeEmail
        c.riskDeadlineDays = deadlineDays
        return c
    }

    @Test
    fun `a non-DIRECT mode with the legacy flag is refused, not guessed`() {
        for (mode in listOf(ImportCommand.OnboardingMode.WELCOME_ONLY, ImportCommand.OnboardingMode.GUIDED)) {
            assertThat(onboardingCmd(mode = mode, start = true).validateOnboardingOptions())
                .describedAs("mode %s", mode)
                .contains("--start-risk-assessment")
        }
    }

    @Test
    fun `DIRECT accepts the legacy flag alongside it`() {
        assertThat(
            onboardingCmd(
                mode = ImportCommand.OnboardingMode.DIRECT, start = true, useCase = "Cloud Onboarding"
            ).validateOnboardingOptions()
        ).isNull()
    }

    @Test
    fun `DIRECT requires a use case`() {
        assertThat(onboardingCmd(mode = ImportCommand.OnboardingMode.DIRECT).validateOnboardingOptions())
            .contains("--risk-usecase")
    }

    @Test
    fun `a use case on a non-DIRECT mode is refused rather than silently ignored`() {
        // Silently ignoring it is how an operator ends up believing an assessment was scoped.
        for (mode in listOf(ImportCommand.OnboardingMode.WELCOME_ONLY, ImportCommand.OnboardingMode.GUIDED)) {
            assertThat(onboardingCmd(mode = mode, useCase = "Cloud Onboarding").validateOnboardingOptions())
                .describedAs("mode %s", mode)
                .contains("--risk-usecase")
        }
    }

    @Test
    fun `the questionnaire expiry is bounded on both sides`() {
        assertThat(onboardingCmd(mode = ImportCommand.OnboardingMode.GUIDED, expiryDays = 0)
            .validateOnboardingOptions()).contains("--questionnaire-expiry-days")
        assertThat(onboardingCmd(mode = ImportCommand.OnboardingMode.GUIDED, expiryDays = 91)
            .validateOnboardingOptions()).contains("--questionnaire-expiry-days")
        // Inclusive boundaries.
        assertThat(onboardingCmd(mode = ImportCommand.OnboardingMode.GUIDED, expiryDays = 1)
            .validateOnboardingOptions()).isNull()
        assertThat(onboardingCmd(mode = ImportCommand.OnboardingMode.GUIDED, expiryDays = 90)
            .validateOnboardingOptions()).isNull()
    }

    @Test
    fun `welcome-email without a mode is refused`() {
        assertThat(onboardingCmd(welcomeEmail = true).validateOnboardingOptions())
            .contains("--onboarding-mode")
        // With a mode, or with the legacy flag, it is meaningful.
        assertThat(onboardingCmd(mode = ImportCommand.OnboardingMode.GUIDED, welcomeEmail = false)
            .validateOnboardingOptions()).isNull()
        assertThat(onboardingCmd(start = true, useCase = "Cloud Onboarding", welcomeEmail = true)
            .validateOnboardingOptions()).isNull()
    }

    @Test
    fun `DIRECT enforces the same deadline bounds as the legacy flag`() {
        val max = UserMappingCliService.MAX_RISK_DEADLINE_DAYS
        assertThat(
            onboardingCmd(mode = ImportCommand.OnboardingMode.DIRECT, useCase = "u", deadlineDays = 0)
                .validateOnboardingOptions()
        ).contains("--risk-deadline-days")
        assertThat(
            onboardingCmd(mode = ImportCommand.OnboardingMode.DIRECT, useCase = "u", deadlineDays = max + 1)
                .validateOnboardingOptions()
        ).contains("--risk-deadline-days")
    }

    @Test
    fun `effectiveMode mirrors the backend fallback exactly`() {
        // This is the backward-compatibility contract as the CLI sees it: an operator who has
        // always passed --start-risk-assessment keeps getting DIRECT and nothing else changes.
        assertThat(onboardingCmd().effectiveMode()).isNull()
        assertThat(onboardingCmd(start = true, useCase = "u").effectiveMode())
            .isEqualTo(ImportCommand.OnboardingMode.DIRECT)
        assertThat(onboardingCmd(mode = ImportCommand.OnboardingMode.WELCOME_ONLY).effectiveMode())
            .isEqualTo(ImportCommand.OnboardingMode.WELCOME_ONLY)
        assertThat(onboardingCmd(mode = ImportCommand.OnboardingMode.GUIDED).effectiveMode())
            .isEqualTo(ImportCommand.OnboardingMode.GUIDED)
    }

    @Test
    fun `the CLI onboarding modes match the backend enum`() {
        // A fourth copy of the mode list would be a liability; there are already three (backend
        // enum, this enum, the MCP schema string list). Pin this one.
        assertThat(ImportCommand.OnboardingMode.entries.map { it.name })
            .containsExactly("WELCOME_ONLY", "DIRECT", "GUIDED")
    }

    @Test
    fun `the CLI questionnaire expiry bounds match the backend bounds`() {
        assertThat(ImportCommand.MIN_QUESTIONNAIRE_EXPIRY_DAYS).isEqualTo(1)
        assertThat(ImportCommand.MAX_QUESTIONNAIRE_EXPIRY_DAYS).isEqualTo(90)
    }
}
