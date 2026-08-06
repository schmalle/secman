package com.secman.service

import com.secman.domain.AwsAccountSharingCreatedEvent
import io.micronaut.transaction.annotation.TransactionalEventListener
import io.micronaut.transaction.annotation.TransactionalEventListener.TransactionPhase
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * AFTER_COMMIT listener that triggers the email notification to the
 * target user of a newly created AwsAccountSharing rule.
 *
 * Lives outside AwsAccountSharingService so the @Transactional boundary
 * never encloses notification I/O. Exceptions are swallowed and logged
 * — a failed notification must never affect the data outcome.
 */
@Singleton
open class AwsAccountSharingNotificationListener(
    private val notificationService: AwsAccountSharingNotificationService,
) {
    private val log = LoggerFactory.getLogger(AwsAccountSharingNotificationListener::class.java)

    @TransactionalEventListener(TransactionPhase.AFTER_COMMIT)
    open fun onCreated(event: AwsAccountSharingCreatedEvent) {
        try {
            notificationService.notifyTargetOfNewShare(event)
        } catch (e: Exception) {
            log.error("Failed to dispatch AWS sharing notification (sharingId={}): {}",
                event.sharingId, e.message)
        }
    }
}
