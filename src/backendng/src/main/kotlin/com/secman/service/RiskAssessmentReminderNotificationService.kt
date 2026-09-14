package com.secman.service

import com.secman.config.AppConfig
import com.secman.repository.RiskAssessmentRepository
import com.secman.util.EmailAddressValidator
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
open class RiskAssessmentReminderNotificationService(
    private val emailService: EmailService,
    private val templateRenderer: EmailTemplateRenderer,
    private val appConfig: AppConfig,
    private val riskAssessmentRepository: RiskAssessmentRepository
) {
    private val log = LoggerFactory.getLogger(RiskAssessmentReminderNotificationService::class.java)

    enum class SendOutcome { DRY_RUN, NO_OUTSTANDING_ANSWERS, COOLDOWN_ACTIVE, SENT, EMAIL_DELIVERY_FAILED }

    open fun send(
        reminder: RiskAssessmentMcpService.OutstandingReminder,
        actorId: Long,
        dryRun: Boolean
    ): SendOutcome {
        require(EmailAddressValidator.isValidRecipient(reminder.recipientEmail)) {
            "Respondent email is not a valid notification recipient"
        }
        if (dryRun) return SendOutcome.DRY_RUN
        if (reminder.unansweredCount == 0) return SendOutcome.NO_OUTSTANDING_ANSWERS

        val claimedAt = java.time.LocalDateTime.now()
        val claimed = riskAssessmentRepository.claimOutstandingReminder(
            reminder.assessmentId,
            claimedAt,
            claimedAt.minusHours(24)
        )
        if (claimed == 0) return SendOutcome.COOLDOWN_ACTIVE

        val useCases = reminder.useCaseNames.joinToString(", ").ifBlank { "Unspecified" }
        val account = reminder.awsAccountId ?: "Not applicable"
        val assessmentUrl = appConfig.backend.baseUrl.trimEnd('/') +
            "/risk-assessments?assessmentId=${reminder.assessmentId}"
        val subject = "Reminder: ${reminder.unansweredCount} risk assessment answer(s) outstanding"
        val text = """
            You have ${reminder.unansweredCount} outstanding answer(s) in a SecMan risk assessment.

            AWS account: $account
            Use cases: $useCases
            Deadline: ${reminder.endDate}
            Open assessment: $assessmentUrl
        """.trimIndent()
        val html = "<pre>${templateRenderer.escapeHtml(text)}</pre>"
        val sent = emailService.sendNotificationEmail(
            reminder.assessmentId,
            reminder.recipientEmail,
            subject,
            text,
            html
        ).get()
        if (sent) {
            log.info(
                "MCP actor {} notified respondent for risk assessment {} with {} outstanding answer(s)",
                actorId, reminder.assessmentId, reminder.unansweredCount
            )
            return SendOutcome.SENT
        } else {
            riskAssessmentRepository.releaseOutstandingReminderClaim(reminder.assessmentId, claimedAt)
            log.warn("MCP actor {} failed to notify respondent for risk assessment {}", actorId, reminder.assessmentId)
            return SendOutcome.EMAIL_DELIVERY_FAILED
        }
    }
}
