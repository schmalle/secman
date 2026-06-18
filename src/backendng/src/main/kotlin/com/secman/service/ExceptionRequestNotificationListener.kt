package com.secman.service

import com.secman.config.ExceptionNotificationConfig
import com.secman.domain.ExceptionRequestApprovedEvent
import com.secman.domain.ExceptionRequestCreatedPendingEvent
import com.secman.domain.ExceptionRequestRejectedEvent
import io.micronaut.transaction.annotation.TransactionalEventListener
import io.micronaut.transaction.annotation.TransactionalEventListener.TransactionPhase
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * AFTER_COMMIT listener that dispatches email notifications for exception-request
 * lifecycle transitions. Lives outside the request service so the @Transactional
 * boundary never encloses notification I/O.
 */
@Singleton
open class ExceptionRequestNotificationListener(
    private val notificationService: ExceptionRequestNotificationService,
    private val notificationConfig: ExceptionNotificationConfig
) {
    private val log = LoggerFactory.getLogger(ExceptionRequestNotificationListener::class.java)

    @TransactionalEventListener(TransactionPhase.AFTER_COMMIT)
    open fun onApproved(event: ExceptionRequestApprovedEvent) {
        try {
            notificationService.notifyRequesterOfApproval(event.request)
        } catch (e: Exception) {
            log.error("Failed to dispatch approval notification for requestId={}: {}",
                event.request.id, e.message)
        }
    }

    @TransactionalEventListener(TransactionPhase.AFTER_COMMIT)
    open fun onRejected(event: ExceptionRequestRejectedEvent) {
        try {
            notificationService.notifyRequesterOfRejection(event.request)
        } catch (e: Exception) {
            log.error("Failed to dispatch rejection notification for requestId={}: {}",
                event.request.id, e.message)
        }
    }

    @TransactionalEventListener(TransactionPhase.AFTER_COMMIT)
    open fun onCreatedPending(event: ExceptionRequestCreatedPendingEvent) {
        // Default (digest) mode: do nothing here — the request stays admin_notified_at=NULL
        // and is picked up by ExceptionRequestDigestScheduler, which pools all new pending
        // requests into a single per-reviewer email. This prevents the 100-requests =>
        // 100-emails-per-reviewer flood. "immediate" mode preserves the legacy per-request
        // blast as a rollback path.
        if (notificationConfig.isDigestMode()) {
            log.debug("Digest mode: deferring new-request notification for requestId={}",
                event.request.id)
            return
        }
        try {
            notificationService.notifyAdminsOfNewRequest(event.request)
        } catch (e: Exception) {
            log.error("Failed to dispatch new-request notification for requestId={}: {}",
                event.request.id, e.message)
        }
    }
}
