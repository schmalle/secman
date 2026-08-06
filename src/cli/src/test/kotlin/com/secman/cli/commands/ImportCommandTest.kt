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
}
