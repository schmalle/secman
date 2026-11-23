package com.secman.service

import io.micronaut.transaction.annotation.TransactionalEventListener
import io.micronaut.transaction.annotation.TransactionalEventListener.TransactionPhase
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDate

data class BatchDecisionEvent(val id: String, val recipients: List<String>, val status: String, val through: LocalDate)

@Singleton
open class ProductBatchDecisionListener(
    private val mail: EmailService, private val cache: ActiveExceptionsCacheInvalidator
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(TransactionPhase.AFTER_COMMIT)
    open fun onDecision(event: BatchDecisionEvent) {
        cache.invalidate()
        val text = "Product exception batch ${event.id} is ${event.status}. Coverage requested through ${event.through} UTC. Open Vulnerability Exceptions in SecMan to view this batch."
        event.recipients.forEach { email ->
            try {
                mail.sendEmail(email, "SecMan product exception batch ${event.status}", text, text).whenComplete { sent, error ->
                    if (sent != true || error != null) log.warn("Batch decision notification failed for batch {}", event.id)
                }
            } catch (error: RuntimeException) {
                log.warn("Could not dispatch batch decision notification for {}", event.id, error)
            }
        }
    }
}
