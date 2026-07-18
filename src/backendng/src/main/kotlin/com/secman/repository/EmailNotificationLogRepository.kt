package com.secman.repository

import com.secman.domain.EmailNotificationLog
import com.secman.domain.enums.EmailStatus
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import java.time.LocalDateTime

/**
 * Repository for EmailNotificationLog entities
 */
@Repository
interface EmailNotificationLogRepository : JpaRepository<EmailNotificationLog, Long> {

    /**
     * Find logs by risk assessment ID
     */
    fun findByRiskAssessmentId(riskAssessmentId: Long): List<EmailNotificationLog>

    /**
     * Find logs by risk assessment ID with pagination
     */
    fun findByRiskAssessmentId(riskAssessmentId: Long, pageable: Pageable): Page<EmailNotificationLog>

    /**
     * Find logs by status
     */
    fun findByStatus(status: EmailStatus): List<EmailNotificationLog>

    /**
     * Find logs by status with pagination
     */
    fun findByStatus(status: EmailStatus, pageable: Pageable): Page<EmailNotificationLog>

    /**
     * Find logs by multiple statuses
     */
    @Query("SELECT e FROM EmailNotificationLog e WHERE e.status IN :statuses")
    fun findByStatusIn(statuses: List<EmailStatus>): List<EmailNotificationLog>

    /**
     * Find failed notifications that can be retried
     */
    @Query("""
        SELECT e FROM EmailNotificationLog e
        WHERE e.status IN ('FAILED', 'RETRYING')
        AND e.attempts < :maxAttempts
        ORDER BY e.updatedAt DESC
    """)
    fun findRetriableNotifications(maxAttempts: Int): List<EmailNotificationLog>

    /**
     * Find notifications sent within time period
     */
    @Query("SELECT e FROM EmailNotificationLog e WHERE e.sentAt BETWEEN :startDate AND :endDate")
    fun findSentBetween(startDate: LocalDateTime, endDate: LocalDateTime): List<EmailNotificationLog>

    /**
     * Find notifications created within time period
     */
    @Query("SELECT e FROM EmailNotificationLog e WHERE e.createdAt BETWEEN :startDate AND :endDate")
    fun findCreatedBetween(startDate: LocalDateTime, endDate: LocalDateTime): List<EmailNotificationLog>

    /**
     * Count notifications by status
     */
    fun countByStatus(status: EmailStatus): Long

    /**
     * Count notifications by risk assessment
     */
    fun countByRiskAssessmentId(riskAssessmentId: Long): Long

    /**
     * Update notification status
     */
    @Query("""
        UPDATE EmailNotificationLog e
        SET e.status = :status,
            e.updatedAt = :updatedAt
        WHERE e.id = :id
    """)
    fun updateStatus(id: Long, status: EmailStatus, updatedAt: LocalDateTime): Int

    /**
     * Mark as sent
     */
    @Query("""
        UPDATE EmailNotificationLog e
        SET e.status = 'SENT',
            e.messageId = :messageId,
            e.sentAt = :sentAt,
            e.updatedAt = :updatedAt
        WHERE e.id = :id
    """)
    fun markAsSent(id: Long, messageId: String, sentAt: LocalDateTime, updatedAt: LocalDateTime): Int

    /**
     * Find logs with pagination and optional filters
     */
    @Query(
        value = """
            SELECT e FROM EmailNotificationLog e
            WHERE (:riskAssessmentId IS NULL OR e.riskAssessmentId = :riskAssessmentId)
            AND (:status IS NULL OR e.status = :status)
            AND (:recipientEmail IS NULL OR LOWER(e.recipientEmail) LIKE LOWER(CONCAT('%', :recipientEmail, '%')))
            ORDER BY e.createdAt DESC
        """,
        countQuery = """
            SELECT COUNT(e) FROM EmailNotificationLog e
            WHERE (:riskAssessmentId IS NULL OR e.riskAssessmentId = :riskAssessmentId)
            AND (:status IS NULL OR e.status = :status)
            AND (:recipientEmail IS NULL OR LOWER(e.recipientEmail) LIKE LOWER(CONCAT('%', :recipientEmail, '%')))
        """
    )
    fun findWithFilters(
        riskAssessmentId: Long?,
        status: EmailStatus?,
        recipientEmail: String?,
        pageable: Pageable
    ): Page<EmailNotificationLog>
}