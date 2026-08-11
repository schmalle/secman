package com.secman.scheduler

import com.secman.service.AccountOnboardingService
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Daily job nudging account owners whose guided-onboarding questionnaire link is about to
 * expire, and expiring the ones that already lapsed.
 *
 * Runs at 08:25, ten minutes after
 * [AwsAccountRiskAssessmentReminderScheduler] — a different minute on purpose, so the two
 * runs do not contend for the mail path or the connection pool at the same instant.
 *
 * All window, claim and dedup logic lives in
 * [AccountOnboardingService.processInviteReminders]; this class only decides *when*.
 */
@Singleton
open class AccountOnboardingReminderScheduler(
    private val accountOnboardingService: AccountOnboardingService
) {
    private val logger = LoggerFactory.getLogger(AccountOnboardingReminderScheduler::class.java)

    @Scheduled(cron = "0 25 8 * * ?")
    open fun sendInviteReminders() {
        logger.info("Starting account onboarding invite reminder processing")
        try {
            val sent = accountOnboardingService.processInviteReminders()
            logger.info("Account onboarding invite reminder processing completed: {} sent", sent)
        } catch (e: Exception) {
            // Logged, never swallowed silently (A09): a scheduler that fails quietly looks
            // identical to one with nothing to do.
            logger.error("Failed to process account onboarding invite reminders", e)
        }
    }
}
