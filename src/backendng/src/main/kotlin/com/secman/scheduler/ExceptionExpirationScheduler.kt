package com.secman.scheduler

import com.secman.domain.ExceptionRequestStatus
import com.secman.repository.VulnerabilityExceptionRequestRepository
import com.secman.repository.VulnerabilityExceptionRepository
import com.secman.service.ExceptionRequestAuditService
import com.secman.service.ExceptionRequestNotificationService
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Scheduled job for managing vulnerability exception request lifecycle.
 *
 * **Job 1: Daily Expiration Processing** (midnight)
 * - Finds APPROVED requests past expiration date
 * - Updates status to EXPIRED
 * - Deactivates corresponding VulnerabilityException entries
 * - Sends expiration notification to requester
 *
 * **Job 2: Expiration Reminder** (8am daily)
 * - Finds APPROVED requests expiring within 7 days
 * - Sends reminder email to requester
 * - Tracks sent reminders to avoid duplicates
 *
 * **Audit Trail**:
 * - Each expiration is logged via ExceptionRequestAuditService
 * - Logs include expiration reason and timestamp
 *
 * Feature: 031-vuln-exception-approval
 * User Story 6: Email Notifications (P3)
 * Phase 10: Email Notifications
 * Reference: spec.md FR-027, FR-028, acceptance scenario US6-3
 */
@Singleton
open class ExceptionExpirationScheduler(
    @Inject private val requestRepository: VulnerabilityExceptionRequestRepository,
    @Inject private val exceptionRepository: VulnerabilityExceptionRepository,
    @Inject private val auditService: ExceptionRequestAuditService,
    @Inject private val notificationService: ExceptionRequestNotificationService
) {
    private val logger = LoggerFactory.getLogger(ExceptionExpirationScheduler::class.java)

    // Track which requests have received reminders to avoid duplicate sends
    private val remindersSent = mutableSetOf<Long>()

    /**
     * Daily job to expire old exception requests.
     *
     * **Schedule**: Every day at midnight (00:00:00)
     * **Cron**: "0 0 0 * * ?" - Second Minute Hour DayOfMonth Month DayOfWeek
     *
     * **Process**:
     * 1. Find APPROVED requests with expiration_date <= today
     * 2. Update status to EXPIRED
     * 3. Find and deactivate corresponding VulnerabilityException entries
     * 4. Send expiration notification to requester
     * 5. Log audit event
     *
     * **Transactional**: Ensures all updates succeed or roll back together
     */
    @Scheduled(cron = "0 0 0 * * ?")
    @Transactional
    open fun processExpirations() {
        logger.info("Starting daily exception expiration processing")

        try {
            val now = LocalDateTime.now()
            val expiredRequests = requestRepository.findByStatusAndExpirationDateLessThanEqual(
                ExceptionRequestStatus.APPROVED,
                now
            )

            logger.info("Found {} expired exception requests", expiredRequests.size)

            var expiredCount = 0
            var deactivatedCount = 0
            var notificationCount = 0

            for (request in expiredRequests) {
                try {
                    // Update request status to EXPIRED
                    request.status = ExceptionRequestStatus.EXPIRED
                    requestRepository.update(request)
                    expiredCount++

                    logger.debug("Expired request {}: CVE={}, asset={}, expirationDate={}",
                        request.id,
                        request.vulnerability?.vulnerabilityId,
                        request.vulnerability?.asset?.name,
                        request.expirationDate
                    )

                    // Deactivate corresponding exception(s)
                    val deactivated = deactivateExceptionsForRequest(request)
                    deactivatedCount += deactivated

                    // Send expiration notification (non-blocking)
                    try {
                        notificationService.notifyRequesterOfExpiration(request)
                        notificationCount++
                    } catch (e: Exception) {
                        logger.error("Failed to send expiration notification for request {}: {}",
                            request.id, e.message)
                        // Continue processing other requests
                    }

                    // Log audit event (system-initiated expiration)
                    auditService.logExpiration(request)

                } catch (e: Exception) {
                    logger.error("Failed to expire request {}: {}", request.id, e.message, e)
                    // Continue with next request
                }
            }

            logger.info(
                "Expiration processing completed: {} requests expired, {} exceptions deactivated, {} notifications sent",
                expiredCount, deactivatedCount, notificationCount
            )

        } catch (e: Exception) {
            logger.error("Failed to process expirations", e)
        }
    }

    /**
     * Daily job to send expiration reminders.
     *
     * **Schedule**: Every day at 8:00 AM
     * **Cron**: "0 0 8 * * ?" - Second Minute Hour DayOfMonth Month DayOfWeek
     *
     * **Process**:
     * 1. Find APPROVED requests with expiration_date between now and now + 7 days
     * 2. Filter out requests that already received reminders
     * 3. Send reminder email to requester
     * 4. Track sent reminders to prevent duplicates
     *
     * **Duplicate Prevention**:
     * - Reminders tracked in-memory (remindersSent set)
     * - Reset on application restart (acceptable - users get reminder after restart)
     * - Production alternative: Add "reminder_sent_date" column to database
     */
    @Scheduled(cron = "0 0 8 * * ?")
    open fun sendExpirationReminders() {
        logger.info("Starting daily expiration reminder processing")

        try {
            val now = LocalDateTime.now()
            val sevenDaysFromNow = now.plusDays(7)

            // Find requests expiring within 7 days
            val expiringRequests = requestRepository.findByStatusAndExpirationDateBetween(
                ExceptionRequestStatus.APPROVED,
                now,
                sevenDaysFromNow
            )

            logger.info("Found {} exception requests expiring within 7 days", expiringRequests.size)

            var remindersSentCount = 0
            var skippedCount = 0

            for (request in expiringRequests) {
                try {
                    // Skip if reminder already sent for this request
                    if (remindersSent.contains(request.id)) {
                        skippedCount++
                        logger.debug("Skipping reminder for request {} - already sent", request.id)
                        continue
                    }

                    // Send reminder notification
                    val sent = notificationService.notifyRequesterOfExpiration(request).get()

                    if (sent) {
                        remindersSent.add(request.id!!)
                        remindersSentCount++

                        logger.debug("Sent expiration reminder for request {}: CVE={}, asset={}, expiresIn={} days",
                            request.id,
                            request.vulnerability?.vulnerabilityId,
                            request.vulnerability?.asset?.name,
                            java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), request.expirationDate.toLocalDate())
                        )
                    } else {
                        logger.warn("Failed to send expiration reminder for request {}", request.id)
                    }

                } catch (e: Exception) {
                    logger.error("Failed to send reminder for request {}: {}", request.id, e.message)
                    // Continue with next request
                }
            }

            logger.info(
                "Expiration reminder processing completed: {} reminders sent, {} skipped (already sent)",
                remindersSentCount, skippedCount
            )

        } catch (e: Exception) {
            logger.error("Failed to process expiration reminders", e)
        }
    }

    /**
     * Deactivate VulnerabilityException entries for an expired request.
     *
     * **Logic**:
     * - SINGLE_VULNERABILITY scope → Deactivate ASSET-type exception matching asset
     * - CVE_PATTERN scope → Deactivate PRODUCT-type exception matching CVE
     *
     * **Note**: Deactivation means deleting the exception record. Once deleted, the
     * vulnerability will reappear in active reports on next scan.
     *
     * @param request The expired exception request
     * @return Number of exceptions deactivated
     */
    private fun deactivateExceptionsForRequest(request: com.secman.domain.VulnerabilityExceptionRequest): Int {
        if (request.vulnerability == null) {
            logger.warn("Cannot deactivate exceptions: vulnerability is null for requestId={}", request.id)
            return 0
        }

        var deactivatedCount = 0

        try {
            when (request.scope) {
                com.secman.domain.ExceptionScope.SINGLE_VULNERABILITY -> {
                    // Find ASSET-type exceptions matching this asset
                    val assetId = request.vulnerability!!.asset.id
                    val exceptions = exceptionRepository.findByExceptionTypeAndAssetId(
                        com.secman.domain.VulnerabilityException.ExceptionType.ASSET,
                        assetId!!
                    )

                    for (exception in exceptions) {
                        // Check if this exception was created for this request
                        // (matches expiration date and reason)
                        if (exception.expirationDate == request.expirationDate &&
                            exception.reason == request.reason
                        ) {
                            exceptionRepository.delete(exception)
                            deactivatedCount++
                            logger.debug("Deactivated ASSET exception {} for asset {}", exception.id, assetId)
                        }
                    }
                }

                com.secman.domain.ExceptionScope.CVE_PATTERN -> {
                    // Find PRODUCT-type exceptions matching this CVE
                    val cveId = request.vulnerability!!.vulnerabilityId ?: return 0
                    val exceptions = exceptionRepository.findByExceptionTypeAndTargetValue(
                        com.secman.domain.VulnerabilityException.ExceptionType.PRODUCT,
                        cveId
                    )

                    for (exception in exceptions) {
                        // Check if this exception was created for this request
                        if (exception.expirationDate == request.expirationDate &&
                            exception.reason == request.reason
                        ) {
                            exceptionRepository.delete(exception)
                            deactivatedCount++
                            logger.debug("Deactivated PRODUCT exception {} for CVE {}", exception.id, cveId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to deactivate exceptions for request {}: {}", request.id, e.message, e)
        }

        return deactivatedCount
    }

    /**
     * Clear the reminders sent tracking (for testing purposes).
     *
     * In production, this would reset on application restart.
     * For persistent tracking, add "reminder_sent_date" column to database.
     */
    open fun clearReminderTracking() {
        logger.info("Clearing reminder tracking ({} entries)", remindersSent.size)
        remindersSent.clear()
    }
}
