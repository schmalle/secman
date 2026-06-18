package com.secman.scheduler

import com.secman.config.ExceptionNotificationConfig
import com.secman.domain.ExceptionRequestStatus
import com.secman.repository.VulnerabilityExceptionRequestRepository
import com.secman.service.ExceptionRequestNotificationService
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Scheduled job that pools new pending exception requests into a single digest email
 * per ADMIN/SECCHAMPION reviewer.
 *
 * **Problem**: previously every PENDING exception request triggered one email per reviewer
 * (via ExceptionRequestNotificationListener.onCreatedPending). 100 requests => 100 emails
 * per reviewer.
 *
 * **Fix**: in digest mode (the default), the listener no longer sends on creation. New
 * requests stay with `admin_notified_at = NULL`. This job runs on a fixed interval
 * (default hourly, EXCEPTION_NOTIFICATIONS_DIGEST_INTERVAL), collects all un-notified
 * PENDING requests, sends one consolidated digest per reviewer, then stamps
 * `admin_notified_at` so they are never re-announced. When there is nothing pending it
 * sends nothing.
 *
 * Mirrors the structure of ExceptionExpirationScheduler.
 */
@Singleton
open class ExceptionRequestDigestScheduler(
    @Inject private val requestRepository: VulnerabilityExceptionRequestRepository,
    @Inject private val notificationService: ExceptionRequestNotificationService,
    @Inject private val notificationConfig: ExceptionNotificationConfig
) {
    private val logger = LoggerFactory.getLogger(ExceptionRequestDigestScheduler::class.java)

    /**
     * Collect un-notified PENDING requests and send one digest email per reviewer.
     *
     * **Schedule**: fixed delay, default 1h (EXCEPTION_NOTIFICATIONS_DIGEST_INTERVAL).
     * The first run is delayed so the application finishes starting up first.
     *
     * **Idempotency**: requests are stamped with `admin_notified_at` once a send has been
     * attempted with at least one success, matching the `successCount > 0` contract of the
     * notification service. This avoids both re-announcing and tight resend loops on a
     * transient SMTP failure (a fully-failed batch is retried on the next run).
     */
    @Scheduled(fixedDelay = "\${secman.exception-notifications.digest.interval:1h}", initialDelay = "2m")
    @Transactional
    open fun sendPendingDigest() {
        if (!notificationConfig.isDigestMode()) {
            logger.debug("Exception-notification mode is 'immediate'; skipping digest run")
            return
        }

        try {
            val pending = requestRepository.findByStatusAndAdminNotifiedAtIsNullOrderByCreatedAt(
                ExceptionRequestStatus.PENDING
            )

            if (pending.isEmpty()) {
                logger.debug("No un-notified pending exception requests; no digest sent")
                return
            }

            logger.info("Sending exception-request digest for {} pending request(s)", pending.size)

            val sent = notificationService.notifyAdminsOfPendingDigest(pending).get()

            if (sent) {
                val now = LocalDateTime.now()
                var stamped = 0
                for (request in pending) {
                    try {
                        request.adminNotifiedAt = now
                        requestRepository.update(request)
                        stamped++
                    } catch (e: Exception) {
                        logger.error("Failed to stamp admin_notified_at for request {}: {}",
                            request.id, e.message)
                    }
                }
                logger.info("Exception-request digest sent; stamped {} request(s) as notified", stamped)
            } else {
                logger.warn("Exception-request digest send reported no successful deliveries; " +
                    "leaving {} request(s) un-notified for retry on next run", pending.size)
            }

        } catch (e: Exception) {
            logger.error("Failed to process exception-request digest", e)
        }
    }
}
