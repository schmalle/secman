package com.secman.repository

import com.secman.domain.CyberdefTicketNotification
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Pageable
import java.time.Instant

/** Mail outbox; claim/finish are conditional updates so only one dispatcher sends a row. */
@Repository
interface CyberdefTicketNotificationRepository : JpaRepository<CyberdefTicketNotification, Long> {

    fun findByTicketIdOrderByIdAsc(ticketId: Long): List<CyberdefTicketNotification>

    fun findByTicketIdAndStatus(ticketId: Long, status: String): List<CyberdefTicketNotification>

    /** Rows nobody claimed, e.g. after a crash between commit and dispatch. */
    @Query(
        """
        SELECT n FROM CyberdefTicketNotification n
        WHERE n.status = 'PENDING' AND n.createdAt < :before ORDER BY n.id ASC
        """
    )
    fun findStalePending(before: Instant, pageable: Pageable): List<CyberdefTicketNotification>

    /** Atomic claim: only one dispatcher can move a row from [expected] to SENDING. */
    @Query(
        """
        UPDATE CyberdefTicketNotification n
        SET n.status = 'SENDING', n.attempts = n.attempts + 1, n.updatedAt = :now
        WHERE n.id = :id AND n.status = :expected
        """
    )
    fun claim(id: Long, expected: String, now: Instant): Int

    @Query(
        """
        UPDATE CyberdefTicketNotification n
        SET n.status = :status, n.errorCode = :errorCode, n.updatedAt = :now, n.sentAt = :sentAt
        WHERE n.id = :id AND n.status = 'SENDING'
        """
    )
    fun finish(id: Long, status: String, errorCode: String?, now: Instant, sentAt: Instant?): Int
}
