package com.secman.repository

import com.secman.domain.OwnerMailNotification
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import java.time.LocalDateTime
import java.util.Optional

@Repository
interface OwnerMailNotificationRepository : JpaRepository<OwnerMailNotification, Long> {
    fun findByEventKey(eventKey: String): Optional<OwnerMailNotification>
    fun findByOwnerEmailIsNotNullOrderByCreatedAtDesc(pageable: Pageable): Page<OwnerMailNotification>

    @Query(value = """
        INSERT IGNORE INTO owner_mail_notification
            (event_key, aws_account_id, owner_email, status, created_at, updated_at)
        VALUES (:eventKey, :accountId, :email, 'PENDING', :now, :now)
    """, nativeQuery = true)
    fun reserve(eventKey: String, accountId: String, email: String, now: LocalDateTime): Int

    @Query("""
        UPDATE OwnerMailNotification n SET n.status = 'PENDING', n.claimedAt = :now, n.updatedAt = :now,
            n.errorCode = NULL
        WHERE n.id = :id AND n.ownerEmail IS NOT NULL AND n.createdAt > :cutoff
            AND ((:retry = true AND n.status = 'FAILED') OR
                 (:retry = false AND n.status = 'PENDING' AND n.claimedAt IS NULL))
    """)
    fun claim(id: Long, retry: Boolean, now: LocalDateTime, cutoff: LocalDateTime): Int

    @Query("""
        UPDATE OwnerMailNotification n SET n.status = :status, n.errorCode = :errorCode,
            n.providerMessageId = :messageId, n.updatedAt = :now
        WHERE n.id = :id AND n.status = 'PENDING'
    """)
    fun finish(id: Long, status: String, errorCode: String?, messageId: String?, now: LocalDateTime): Int

    @Query("""
        UPDATE OwnerMailNotification n SET n.ownerEmail = NULL, n.awsAccountId = NULL,
            n.errorCode = NULL, n.providerMessageId = NULL, n.claimedAt = NULL
        WHERE n.createdAt <= :cutoff AND n.ownerEmail IS NOT NULL
    """)
    fun redactExpired(cutoff: LocalDateTime): Int
}
