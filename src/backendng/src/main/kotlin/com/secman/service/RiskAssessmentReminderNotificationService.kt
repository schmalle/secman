package com.secman.service

import com.secman.config.AppConfig
import com.secman.repository.AssessmentAssignmentRepository
import com.secman.repository.AssessmentTokenRepository
import com.secman.util.EmailAddressValidator
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
open class RiskAssessmentReminderNotificationService(
    private val emailService: EmailService,
    private val workflow: AssessmentWorkflowService,
    private val templateRenderer: EmailTemplateRenderer,
    private val appConfig: AppConfig,
    private val assignments: AssessmentAssignmentRepository,
    private val tokens: AssessmentTokenRepository
) {
    private val log = LoggerFactory.getLogger(RiskAssessmentReminderNotificationService::class.java)

    enum class SendOutcome { DRY_RUN, NO_OUTSTANDING_ANSWERS, COOLDOWN_ACTIVE, SENT, EMAIL_DELIVERY_FAILED }

    open fun send(
        reminder: RiskAssessmentMcpService.OutstandingReminder,
        actorId: Long,
        dryRun: Boolean,
        apiKeyId: Long? = null
    ): SendOutcome {
        require(EmailAddressValidator.isValidRecipient(reminder.recipientEmail)) {
            "Respondent email is not a valid notification recipient"
        }
        if (dryRun) return SendOutcome.DRY_RUN
        if (reminder.unansweredCount == 0) return SendOutcome.NO_OUTSTANDING_ANSWERS

        checkCurrent(reminder, actorId, apiKeyId)
        val claimedAt = java.time.LocalDateTime.now()
        val claimed = assignments.claimReminder(
            reminder.assignmentId, reminder.assignmentVersion,
            claimedAt,
            claimedAt.minusHours(24)
        )
        if (claimed == 0) return SendOutcome.COOLDOWN_ACTIVE

        try {
            val useCases = reminder.useCaseNames.joinToString(", ").ifBlank { "Unspecified" }
            val account = reminder.awsAccountId ?: "Not applicable"
            val token = if (reminder.accountless) tokens.save(workflow.issueReminderToken(reminder, actorId, apiKeyId)) else null
            val assessmentUrl = appConfig.backend.baseUrl.trimEnd('/') +
                if (token == null) "/risk-assessments?assessmentId=${reminder.assessmentId}" else "/respond/${token.token}"
            val subject = "Reminder: ${reminder.unansweredCount} risk assessment answer(s) outstanding"
            val text = """
                You have ${reminder.unansweredCount} outstanding answer(s) in a SecMan risk assessment.

                AWS account: $account
                Use cases: $useCases
                Deadline: ${reminder.endDate}
                Open assessment: $assessmentUrl
            """.trimIndent()
            val html = "<pre>${templateRenderer.escapeHtml(text)}</pre>"
            checkCurrent(reminder, actorId, apiKeyId)
            val sent = emailService.sendNotificationEmail(
                reminder.assessmentId,
                reminder.recipientEmail,
                subject,
                text,
                html,
                beforeSend = { checkCurrent(reminder, actorId, apiKeyId) }
            ).get()
            if (sent) {
                log.info(
                    "MCP actor {} notified respondent for risk assessment {} with {} outstanding answer(s)",
                    actorId, reminder.assessmentId, reminder.unansweredCount
                )
                return SendOutcome.SENT
            } else {
                assignments.releaseReminder(reminder.assignmentId, claimedAt)
                log.warn("MCP actor {} failed to notify respondent for risk assessment {}", actorId, reminder.assessmentId)
                return SendOutcome.EMAIL_DELIVERY_FAILED
            }
        } catch (failure: Exception) {
            assignments.releaseReminder(reminder.assignmentId, claimedAt)
            throw failure
        }
    }
    private fun checkCurrent(reminder: RiskAssessmentMcpService.OutstandingReminder, actorId: Long, apiKeyId: Long?) {
        workflow.authorizeReminder(reminder.assessmentId, actorId, reminder.recipientEmail, apiKeyId)
        val assignment = assignments.findById(reminder.assignmentId).orElseThrow { SecurityException("Assignment revoked") }
        check(!assignment.revoked && !assignment.submitted && assignment.assessmentId == reminder.assessmentId &&
            assignment.version == reminder.assignmentVersion && assignment.email.equals(reminder.recipientEmail, true)) {
            "Reminder assignment changed"
        }
    }

}
