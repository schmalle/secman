package com.secman.service

import com.secman.config.AppConfig
import com.secman.config.BackendConfig
import com.secman.repository.AssessmentAssignmentRepository
import com.secman.domain.AssessmentAssignment
import java.util.Optional
import org.junit.jupiter.api.BeforeEach
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.concurrent.CompletableFuture

class RiskAssessmentReminderNotificationServiceTest {
    private val emailService = mockk<EmailService>()
    private val renderer = mockk<EmailTemplateRenderer>()
    private val repository = mockk<AssessmentAssignmentRepository>()
    private val service = RiskAssessmentReminderNotificationService(
        emailService,
        mockk(relaxed = true),
        renderer,
        AppConfig(backend = BackendConfig(baseUrl = "https://secman.example.com")),
        repository,
        mockk(relaxed = true)
    )

    /** Creates a current respondent assignment for reminder authorization. */
    @BeforeEach fun assignmentFixture() {
        every { repository.findById(5) } returns Optional.of(AssessmentAssignment(id = 5, assessmentId = 40,
            userId = 8, email = "owner@example.com", role = "RESPONDENT"))
    }

    @Test
    fun `sends outstanding reminder with authenticated assessment link`() {
        every { renderer.escapeHtml(any()) } answers { firstArg() }
        every { repository.claimReminder(5, 1, any(), any()) } returns 1
        every { emailService.sendNotificationEmail(any(), any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(true)

        val sent = service.send(reminder(unanswered = 2), actorId = 7, dryRun = false)

        assertThat(sent).isEqualTo(RiskAssessmentReminderNotificationService.SendOutcome.SENT)
        verify {
            emailService.sendNotificationEmail(
                40,
                "owner@example.com",
                match { it.contains("2") },
                match { it.contains("/risk-assessments?assessmentId=40") && !it.contains("/respond/") },
                any(),
                null,
                any()
            )
        }
    }

    @Test
    fun `dry run and complete questionnaire do not send mail`() {
        assertThat(service.send(reminder(unanswered = 2), actorId = 7, dryRun = true))
            .isEqualTo(RiskAssessmentReminderNotificationService.SendOutcome.DRY_RUN)
        assertThat(service.send(reminder(unanswered = 0), actorId = 7, dryRun = false))
            .isEqualTo(RiskAssessmentReminderNotificationService.SendOutcome.NO_OUTSTANDING_ANSWERS)

        verify(exactly = 0) { emailService.sendNotificationEmail(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cooldown prevents a repeated reminder`() {
        every { repository.claimReminder(5, 1, any(), any()) } returns 0

        val result = service.send(reminder(unanswered = 2), actorId = 7, dryRun = false)

        assertThat(result).isEqualTo(RiskAssessmentReminderNotificationService.SendOutcome.COOLDOWN_ACTIVE)
        verify(exactly = 0) { emailService.sendNotificationEmail(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `reassignment at delivery prevents mail and releases the cooldown claim`() {
        val assignment = AssessmentAssignment(id = 5, assessmentId = 40, userId = 8,
            email = "owner@example.com", role = "RESPONDENT")
        every { repository.findById(5) } returns Optional.of(assignment)
        every { repository.claimReminder(5, 1, any(), any()) } returns 1
        every { repository.releaseReminder(5, any()) } returns 1
        every { renderer.escapeHtml(any()) } answers { firstArg() }
        every { emailService.sendNotificationEmail(any(), any(), any(), any(), any(), any(), any()) } answers {
            assignment.version++
            val check = arg<() -> Unit>(6)
            check()
            CompletableFuture.completedFuture(true)
        }
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            service.send(reminder(2), 7, false)
        }
        verify { repository.releaseReminder(5, any()) }
    }

    private fun reminder(unanswered: Int) = RiskAssessmentMcpService.OutstandingReminder(
        assessmentId = 40,
        recipientEmail = "owner@example.com",
        awsAccountId = "123456789012",
        useCaseNames = listOf("Cloud workload", "Sensitive data"),
        endDate = LocalDate.of(2026, 10, 1),
        unansweredCount = unanswered,
        requirementCount = 3,
        assignmentId = 5
    )
}
