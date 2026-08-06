package com.secman.scheduler

import com.secman.service.AwsAccountRiskAssessmentService
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Daily job sending deadline reminders for risk assessments that were
 * auto-started for owners of new AWS accounts during a user-mapping import
 * (CLI: `manage-user-mappings import --start-risk-assessment`).
 *
 * A reminder email goes to the account owner 2 days and 1 day before the
 * assessment's deadline (endDate). Sent-state is persisted on
 * `aws_account_risk_assessment`, so each reminder is sent exactly once even
 * across restarts; only open assessments (status STARTED) are reminded.
 * All window/dedup logic lives in
 * [AwsAccountRiskAssessmentService.processDeadlineReminders].
 */
@Singleton
open class AwsAccountRiskAssessmentReminderScheduler(
    private val awsAccountRiskAssessmentService: AwsAccountRiskAssessmentService
) {
    private val logger = LoggerFactory.getLogger(AwsAccountRiskAssessmentReminderScheduler::class.java)

    /** Every day at 08:15, shortly after the exception-expiration reminders. */
    @Scheduled(cron = "0 15 8 * * ?")
    open fun sendDeadlineReminders() {
        logger.info("Starting AWS-account risk assessment deadline reminder processing")
        try {
            val sent = awsAccountRiskAssessmentService.processDeadlineReminders()
            logger.info("AWS-account risk assessment reminder processing completed: {} sent", sent)
        } catch (e: Exception) {
            logger.error("Failed to process risk assessment deadline reminders", e)
        }
    }
}
