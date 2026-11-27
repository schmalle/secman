package com.secman.service.cyberdef

import com.secman.domain.CyberdefTicket
import com.secman.domain.CyberdefTicketEvent
import com.secman.domain.CyberdefTicketNotification
import com.secman.repository.CyberdefTicketEventRepository
import com.secman.repository.CyberdefTicketNotificationRepository
import com.secman.repository.CyberdefTicketRepository
import com.secman.service.EmailService
import com.secman.service.EmailTemplateRenderer
import io.micronaut.data.model.Pageable
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutorService

/**
 * Sends queued ticket mail. Never called inside a transaction: each row is claimed with a
 * single conditional UPDATE, sent, then finished with another, so two dispatchers (the
 * request thread and the recovery job) can never send the same row twice.
 *
 * Creation mails of one submission (same batch key) to the same recipient go out as one
 * digest, so a 30-finding batch is one mail per owner, not thirty.
 */
@Singleton
open class CyberdefTicketMailer(
    private val notifications: CyberdefTicketNotificationRepository,
    private val tickets: CyberdefTicketRepository,
    private val events: CyberdefTicketEventRepository,
    private val email: EmailService,
    private val templates: EmailTemplateRenderer,
    private val renderer: CyberdefTicketMailRenderer,
    @Named(TaskExecutors.IO) private val executor: ExecutorService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** How many mails a dispatch run actually sent or failed (a digest counts once). */
    data class Tally(val attempted: Int, val sent: Int, val failed: Int)

    /** Sends [notificationIds] on the IO pool; rows that fail to go out stay PENDING for [recoverStalePending]. */
    open fun dispatchLater(notificationIds: List<Long>) {
        if (notificationIds.isEmpty()) return
        executor.execute {
            try {
                dispatch(notificationIds)
            } catch (e: Exception) {
                log.warn("Cyberdef ticket mail dispatch failed count={} exceptionType={}", notificationIds.size, e.javaClass.simpleName)
            }
        }
    }

    /** Sends PENDING rows now, digesting creation mails per batch and recipient. */
    open fun dispatch(notificationIds: Collection<Long>): Tally =
        send(notificationIds, CyberdefTicketNotification.STATUS_PENDING)

    /** Retries only rows that failed cleanly. UNCERTAIN rows may already have been delivered. */
    open fun resendFailed(ticketId: Long): Tally =
        send(notifications.findByTicketIdAndStatus(ticketId, CyberdefTicketNotification.STATUS_FAILED).mapNotNull { it.id },
            CyberdefTicketNotification.STATUS_FAILED)

    /** Picks up rows a crash left between commit and dispatch. */
    @Scheduled(fixedDelay = "5m", initialDelay = "3m")
    open fun recoverStalePending() {
        val stale = notifications.findStalePending(Instant.now().minus(STALE_AFTER), Pageable.from(0, RECOVERY_BATCH))
        if (stale.isNotEmpty()) {
            val tally = dispatch(stale.mapNotNull { it.id })
            log.info("Cyberdef ticket mail recovery attempted={} sent={} failed={}", tally.attempted, tally.sent, tally.failed)
        }
    }

    /** Everything a send run reads, fetched once: rows, their tickets and their events. */
    private class Loaded(val tickets: Map<Long, CyberdefTicket>, val events: Map<Long, CyberdefTicketEvent>)

    private fun send(ids: Collection<Long>, expected: String): Tally {
        if (ids.isEmpty()) return Tally(0, 0, 0)
        val rows = notifications.findByIdIn(ids.toSet()).filter { it.status == expected }
        val loaded = Loaded(
            tickets.findByIdIn(rows.map { it.ticketId }.toSet()).associateBy { it.id!! },
            rows.mapNotNull { it.eventId }.toSet().takeIf { it.isNotEmpty() }?.let { eventIds ->
                events.findByIdIn(eventIds).associateBy { it.id!! }
            }.orEmpty()
        )
        val groups = rows.groupBy { row ->
            if (row.kind == CyberdefTicketWriter.KIND_CREATED && row.batchKey != null) "${row.batchKey}|${row.recipientEmail}"
            else "row:${row.id}"
        }.values
        val outcomes = groups.mapNotNull { group -> sendGroup(group, expected, loaded) }
        return Tally(outcomes.size, outcomes.count { it }, outcomes.count { !it })
    }

    /** Claims the group's rows and sends one mail for those it won; null when it won none. */
    private fun sendGroup(group: List<CyberdefTicketNotification>, expected: String, loaded: Loaded): Boolean? {
        val claimed = group.filter { notifications.claim(it.id!!, expected, Instant.now()) == 1 }
        if (claimed.isEmpty()) return null
        val groupTickets = claimed.mapNotNull { loaded.tickets[it.ticketId] }
        val result = when {
            groupTickets.size != claimed.size -> EmailService.TrackedDelivery("FAILED", "TICKET_MISSING")
            else -> try {
                val mail = if (claimed.size == 1) {
                    renderer.single(claimed.single().kind, groupTickets.single(), claimed.single().eventId?.let { loaded.events[it] })
                } else renderer.digest(groupTickets)
                email.sendTrackedEmailWithInlineImages(claimed.first().recipientEmail, mail.subject, mail.text, mail.html,
                    templates.loadLogoInlineImage())
            } catch (e: Exception) {
                log.warn("Cyberdef ticket mail failed notificationId={} exceptionType={}", claimed.first().id, e.javaClass.simpleName)
                EmailService.TrackedDelivery("PENDING", "DELIVERY_UNCERTAIN")
            }
        }
        val status = when (result.status) {
            "SENT" -> CyberdefTicketNotification.STATUS_SENT
            "FAILED" -> CyberdefTicketNotification.STATUS_FAILED
            else -> CyberdefTicketNotification.STATUS_UNCERTAIN
        }
        val now = Instant.now()
        notifications.finishAll(claimed.mapNotNull { it.id }, status, result.errorCode?.take(64), now,
            if (status == CyberdefTicketNotification.STATUS_SENT) now else null)
        log.info("AUDIT operation=CYBERDEF_TICKET_MAIL notificationIds={} tickets={} kind={} digest={} outcome={}",
            claimed.mapNotNull { it.id }, claimed.map { it.ticketId }, claimed.first().kind, claimed.size > 1, status)
        return status == CyberdefTicketNotification.STATUS_SENT
    }

    companion object {
        private val STALE_AFTER: Duration = Duration.ofMinutes(2)
        private const val RECOVERY_BATCH = 100
    }
}
