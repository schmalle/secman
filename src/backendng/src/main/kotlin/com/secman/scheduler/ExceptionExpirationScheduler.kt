package com.secman.scheduler

import com.secman.domain.ExceptionRequestStatus
import com.secman.repository.VulnerabilityExceptionRequestRepository
import com.secman.repository.VulnerabilityExceptionRepository
import com.secman.service.ActiveExceptionsCacheInvalidator
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
 * User Story 6: Email Notifications (P3)
 * Phase 10: Email Notifications
 * Reference: spec.md FR-027, FR-028, acceptance scenario US6-3
 */
@Singleton
open class ExceptionExpirationScheduler(
    @Inject private val requestRepository: VulnerabilityExceptionRequestRepository,
    @Inject private val exceptionRepository: VulnerabilityExceptionRepository,
    @Inject private val auditService: ExceptionRequestAuditService,
    @Inject private val notificationService: ExceptionRequestNotificationService,
    @Inject private val activeExceptionsCacheInvalidator: ActiveExceptionsCacheInvalidator
) {
    private val logger = LoggerFactory.getLogger(ExceptionExpirationScheduler::class.java)

    /**
     * Self proxy so the claim helpers below run in REQUIRES_NEW transactions even though
     * they are invoked from within this class (Micronaut AOP is bypassed on direct
     * self-invocation - same pattern as CrowdStrikeVulnerabilityImportService).
     */
    @Inject
    private lateinit var selfProvider: jakarta.inject.Provider<ExceptionExpirationScheduler>

    /**
     * Claim the APPROVED→EXPIRED transition in an independently committed transaction.
     * Committing per claim (instead of at the end of the whole scheduler run) makes the
     * claim immediately visible to an overlapping run on another instance, and keeps the
     * row lock held only for this short transaction rather than across email sending.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun claimExpirationNewTx(requestId: Long, now: LocalDateTime): Boolean =
        requestRepository.claimStatusTransition(
            requestId, ExceptionRequestStatus.APPROVED, ExceptionRequestStatus.EXPIRED, now
        ) == 1

    /** Claim the expiration reminder in an independently committed transaction. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun claimReminderNewTx(requestId: Long, now: LocalDateTime): Boolean =
        requestRepository.claimReminder(requestId, now) == 1

    /** Release a reminder claim (best-effort) in an independently committed transaction. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun releaseReminderClaimNewTx(requestId: Long, claimedAt: LocalDateTime) {
        requestRepository.releaseReminderClaim(requestId, claimedAt)
    }

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
     * **Concurrency**: each request is claimed via an atomic guarded UPDATE
     * (APPROVED→EXPIRED) in its own REQUIRES_NEW transaction (committed per
     * request), so overlapping runs (a second app instance at midnight, or a
     * run overlapping a slow previous one) process each request's side effects
     * (deactivation, emails, audit row) exactly once. The outer @Transactional
     * remains for the Hibernate session (lazy vulnerability/asset access) —
     * the loaded entities are intentionally never mutated here, since the claim
     * bumps the row's @Version and a dirty flush at commit would collide with it.
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
                    // Atomically claim the APPROVED→EXPIRED transition (REQUIRES_NEW, commits
                    // immediately); a concurrent run that already expired this request gets a
                    // 0-row no-op and skips all side effects. NOTE: the in-memory entity is
                    // deliberately NOT mutated - the claim already bumped the row's @Version,
                    // so a dirty flush at outer-transaction commit would throw an optimistic-
                    // lock failure and roll back the deactivations below.
                    if (!selfProvider.get().claimExpirationNewTx(request.id!!, now)) {
                        logger.debug("Request {} already expired by a concurrent run - skipping", request.id)
                        continue
                    }
                    expiredCount++

                    logger.debug("Expired request {}: subject={}, scope={}, CVE={}, asset={}, expirationDate={}",
                        request.id,
                        request.subject,
                        request.scope,
                        request.vulnerability?.vulnerabilityId,
                        request.vulnerability?.asset?.name,
                        request.expirationDate
                    )

                    // Deactivate corresponding exception(s)
                    val deactivated = deactivateExceptionsForRequest(request)
                    deactivatedCount += deactivated

                    // Send expiration notifications (non-blocking)
                    try {
                        notificationService.notifyRequesterOfExpiration(request)
                        notificationService.notifyAdminsAndSecChampionsOfExpiration(request)
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

            if (deactivatedCount > 0) {
                activeExceptionsCacheInvalidator.invalidate()
            }

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
     *    whose reminder_sent_at is still NULL
     * 2. Atomically claim the reminder (guarded UPDATE on reminder_sent_at IS NULL),
     *    then send the email - claim-before-send means overlapping runs (second app
     *    instance, overlapping schedule) can never double-send
     * 3. If the send fails after a successful claim, the claim is released (best-effort)
     *    so the next run retries; a lost release skips the reminder rather than
     *    duplicating it
     *
     * Deliberately NOT @Transactional: the reminder query eagerly fetches the associations
     * the email/log fields need (vulnerability, vulnerability.asset, requestedByUser), so no
     * open transaction is required to resolve them — and the blocking SMTP send below must
     * never hold a pooled DB connection across the wait. Each claim still commits independently
     * via REQUIRES_NEW (selfProvider), and entities are never mutated here.
     */
    @Scheduled(cron = "0 0 8 * * ?")
    open fun sendExpirationReminders() {
        logger.info("Starting daily expiration reminder processing")

        try {
            val now = LocalDateTime.now()
            val sevenDaysFromNow = now.plusDays(7)

            val expiringRequests = requestRepository.findByStatusAndExpirationDateBetween(
                ExceptionRequestStatus.APPROVED,
                now,
                sevenDaysFromNow
            ).filter { it.reminderSentAt == null }

            logger.info("Found {} exception requests expiring within 7 days without a reminder", expiringRequests.size)

            var remindersSentCount = 0

            for (request in expiringRequests) {
                try {
                    val claimedAt = LocalDateTime.now()
                    if (!selfProvider.get().claimReminderNewTx(request.id!!, claimedAt)) {
                        logger.debug("Reminder for request {} already claimed by a concurrent run - skipping", request.id)
                        continue
                    }

                    val sent = try {
                        notificationService.notifyRequesterOfExpiration(request).get()
                    } catch (e: Exception) {
                        logger.error("Reminder send threw for request {}: {}", request.id, e.message)
                        false
                    }

                    if (sent) {
                        remindersSentCount++
                        logger.debug("Sent expiration reminder for request {}: CVE={}, asset={}, expiresIn={} days",
                            request.id,
                            request.vulnerability?.vulnerabilityId,
                            request.vulnerability?.asset?.name,
                            java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), request.expirationDate.toLocalDate())
                        )
                    } else {
                        logger.warn("Failed to send expiration reminder for request {} - releasing claim for retry", request.id)
                        selfProvider.get().releaseReminderClaimNewTx(request.id!!, claimedAt)
                    }

                } catch (e: Exception) {
                    logger.error("Failed to send reminder for request {}: {}", request.id, e.message)
                }
            }

            logger.info("Expiration reminder processing completed: {} reminders sent", remindersSentCount)

        } catch (e: Exception) {
            logger.error("Failed to process expiration reminders", e)
        }
    }

    /**
     * Deactivate VulnerabilityException entries for an expired request.
     *
     * Two-axis (Feature 196): match by (subject, scope, subjectValue, scopeValue, assetId,
     * expirationDate, reason). Deactivation means deleting the exception record.
     *
     * @param request The expired exception request
     * @return Number of exceptions deactivated
     */
    private fun deactivateExceptionsForRequest(request: com.secman.domain.VulnerabilityExceptionRequest): Int {
        val effectiveSubjectValue = request.subjectValue ?: when (request.subject) {
            com.secman.domain.VulnerabilityException.Subject.CVE ->
                request.cveId ?: request.vulnerability?.vulnerabilityId
            else -> null
        }
        val effectiveAssetId = if (request.scope == com.secman.domain.VulnerabilityException.Scope.ASSET) {
            request.assetId ?: request.vulnerability?.asset?.id
        } else null

        var deactivatedCount = 0

        try {
            val candidates = exceptionRepository.findBySubjectAndScope(request.subject, request.scope)

            for (exception in candidates) {
                if (exception.subjectValue == effectiveSubjectValue &&
                    exception.scopeValue == request.scopeValue &&
                    exception.assetId == effectiveAssetId &&
                    exception.expirationDate == request.expirationDate &&
                    exception.reason == request.reason
                ) {
                    exceptionRepository.delete(exception)
                    deactivatedCount++
                    logger.debug(
                        "Deactivated exception {} for request {} (subject={}, scope={}, subjectValue={}, scopeValue={}, assetId={})",
                        exception.id, request.id, request.subject, request.scope,
                        effectiveSubjectValue, request.scopeValue, effectiveAssetId
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to deactivate exceptions for request {}: {}", request.id, e.message, e)
        }

        return deactivatedCount
    }


}
