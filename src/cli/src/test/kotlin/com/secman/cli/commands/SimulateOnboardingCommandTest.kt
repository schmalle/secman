package com.secman.cli.commands

import com.secman.cli.service.AccountOnboardingCliService
import io.micronaut.context.ApplicationContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Option validation for the surface that lets you onboard a made-up account.
 *
 * `validateOptions()` never touches the network, so a real wired service is fine. The checks
 * mirror the backend's so the message names the *flag* — and the address check in particular is
 * a security boundary, not cosmetics: a live run hands that value to SMTP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulateOnboardingCommandTest {

    private lateinit var context: ApplicationContext
    private lateinit var service: AccountOnboardingCliService

    @BeforeAll
    fun setup() {
        context = ApplicationContext.builder().environments("cli").start()
        service = context.getBean(AccountOnboardingCliService::class.java)
    }

    @AfterAll
    fun teardown() {
        context.close()
    }

    private fun cmd(
        accountId: String = "999999999999",
        email: String = "you@example.com",
        mode: ImportCommand.OnboardingMode = ImportCommand.OnboardingMode.GUIDED,
        useCase: String? = null,
        deadlineDays: Int = 7,
        expiryDays: Int = 14
    ): SimulateOnboardingCommand {
        val c = SimulateOnboardingCommand(service)
        c.awsAccountId = accountId
        c.ownerEmail = email
        c.mode = mode
        c.riskUseCase = useCase
        c.riskDeadlineDays = deadlineDays
        c.questionnaireExpiryDays = expiryDays
        return c
    }

    @Test
    fun `a well-formed guided simulation is accepted`() {
        assertThat(cmd().validateOptions()).isNull()
    }

    @Test
    fun `the account id must be exactly twelve digits`() {
        for (bad in listOf("", "12345", "9999999999999", "abcdefghijkl", "99999999999a", "999 999 999 999")) {
            assertThat(cmd(accountId = bad).validateOptions())
                .describedAs("'%s'", bad)
                .contains("--aws-account-id")
        }
        assertThat(cmd(accountId = " 999999999999 ").validateOptions()).isNull()
    }

    @Test
    fun `the owner address cannot smuggle a second recipient`() {
        // The CLI copy of the backend's anti-header-injection boundary. A live run passes this
        // value to InternetAddress.parse, where a comma would become two recipients.
        for (bad in listOf(
            "not-an-email",
            "you@example.com,evil@bad.com",
            "you@example.com;evil@bad.com",
            "you@example.com\nBcc: evil@bad.com",
            "Someone <you@example.com>",
            ""
        )) {
            assertThat(cmd(email = bad).validateOptions())
                .describedAs("'%s'", bad)
                .contains("--owner-email")
        }
    }

    @Test
    fun `DIRECT requires a use case and the others refuse one`() {
        assertThat(cmd(mode = ImportCommand.OnboardingMode.DIRECT).validateOptions())
            .contains("--risk-usecase")
        assertThat(cmd(mode = ImportCommand.OnboardingMode.DIRECT, useCase = "Cloud Onboarding").validateOptions())
            .isNull()
        for (mode in listOf(ImportCommand.OnboardingMode.WELCOME_ONLY, ImportCommand.OnboardingMode.GUIDED)) {
            assertThat(cmd(mode = mode, useCase = "Cloud Onboarding").validateOptions())
                .describedAs("mode %s", mode)
                .contains("--risk-usecase")
        }
    }

    @Test
    fun `the deadline is bounded on both sides`() {
        val max = SimulateOnboardingCommand.MAX_RISK_DEADLINE_DAYS
        assertThat(cmd(deadlineDays = 0).validateOptions()).contains("--risk-deadline-days")
        assertThat(cmd(deadlineDays = max + 1).validateOptions()).contains("--risk-deadline-days")
        assertThat(cmd(deadlineDays = max).validateOptions()).isNull()
        assertThat(cmd(deadlineDays = 1).validateOptions()).isNull()
    }

    @Test
    fun `the questionnaire expiry is bounded on both sides`() {
        assertThat(cmd(expiryDays = 0).validateOptions()).contains("--questionnaire-expiry-days")
        assertThat(cmd(expiryDays = 91).validateOptions()).contains("--questionnaire-expiry-days")
        assertThat(cmd(expiryDays = 1).validateOptions()).isNull()
        assertThat(cmd(expiryDays = 90).validateOptions()).isNull()
    }

    @Test
    fun `the CLI deadline cap matches the backend cap`() {
        assertThat(SimulateOnboardingCommand.MAX_RISK_DEADLINE_DAYS).isEqualTo(3650)
    }
}
