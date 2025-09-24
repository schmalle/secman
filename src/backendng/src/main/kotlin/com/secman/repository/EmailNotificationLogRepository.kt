package com.secman.repository

import com.secman.domain.EmailNotificationLog
import com.secman.domain.enums.EmailStatus
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import java.time.LocalDateTime
import java.util.*

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
     * Find logs by email config ID
     */
    fun findByEmailConfigId(emailConfigId: Long): List<EmailNotificationLog>

    /**
     * Find logs by recipient email
     */
    fun findByRecipientEmail(recipientEmail: String): List<EmailNotificationLog>

    /**
     * Find logs by multiple statuses
     */
    @Query("SELECT e FROM EmailNotificationLog e WHERE e.status IN :statuses")
    fun findByStatusIn(statuses: List<EmailStatus>): List<EmailNotificationLog>

    /**
     * Find pending notifications ready for sending
     */
    @Query("SELECT e FROM EmailNotificationLog e WHERE e.status = 'PENDING' ORDER BY e.createdAt ASC")
    fun findPendingNotifications(): List<EmailNotificationLog>

    /**
     * Find notifications ready for retry
     */
    @Query("""
        SELECT e FROM EmailNotificationLog e
        WHERE e.status = 'RETRYING'
        AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= :now)
        ORDER BY e.nextRetryAt ASC NULLS FIRST
    """)
    fun findNotificationsReadyForRetry(now: LocalDateTime): List<EmailNotificationLog>

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
     * Get delivery statistics
     */
    @Query("""
        SELECT new map(
            e.status as status,
            COUNT(e) as count
        )
        FROM EmailNotificationLog e
        GROUP BY e.status
    """)
    fun getDeliveryStatistics(): List<Map<String, Any>>

    /**
     * Get delivery statistics for date range
     */
    @Query("""
        SELECT new map(
            e.status as status,
            COUNT(e) as count
        )
        FROM EmailNotificationLog e
        WHERE e.createdAt BETWEEN :startDate AND :endDate
        GROUP BY e.status
    """)
    fun getDeliveryStatistics(startDate: LocalDateTime, endDate: LocalDateTime): List<Map<String, Any>>

    /**
     * Get success rate for time period
     */
    @Query("""
        SELECT new map(
            'total' as metric,
            COUNT(e) as count
        )
        FROM EmailNotificationLog e
        WHERE e.createdAt BETWEEN :startDate AND :endDate
        UNION ALL
        SELECT new map(
            'successful' as metric,
            COUNT(e) as count
        )
        FROM EmailNotificationLog e
        WHERE e.createdAt BETWEEN :startDate AND :endDate
        AND e.status = 'SENT'
    """)
    fun getSuccessRateData(startDate: LocalDateTime, endDate: LocalDateTime): List<Map<String, Any>>

    /**
     * Find recent activity
     */
    @Query("""
        SELECT e FROM EmailNotificationLog e
        WHERE e.updatedAt >= :since
        ORDER BY e.updatedAt DESC
    """)
    fun findRecentActivity(since: LocalDateTime): List<EmailNotificationLog>

    /**
     * Find overdue notifications
     */
    @Query("""
        SELECT e FROM EmailNotificationLog e
        WHERE (
            (e.status = 'PENDING' AND e.createdAt < :pendingThreshold)
            OR
            (e.status = 'RETRYING' AND e.nextRetryAt < :retryThreshold)
        )
        ORDER BY e.createdAt ASC
    """)
    fun findOverdueNotifications(
        pendingThreshold: LocalDateTime,
        retryThreshold: LocalDateTime
    ): List<EmailNotificationLog>

    /**
     * Find notifications with errors
     */
    @Query("""
        SELECT e FROM EmailNotificationLog e
        WHERE e.status IN ('FAILED', 'PERMANENTLY_FAILED')
        AND e.errorMessage IS NOT NULL
        ORDER BY e.updatedAt DESC
    """)
    fun findNotificationsWithErrors(): List<EmailNotificationLog>

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
     * Update retry information
     */
    @Query("""
        UPDATE EmailNotificationLog e
        SET e.status = :status,
            e.attempts = e.attempts + 1,
            e.nextRetryAt = :nextRetryAt,
            e.errorMessage = :errorMessage,
            e.updatedAt = :updatedAt
        WHERE e.id = :id
    """)
    fun updateRetryInfo(
        id: Long,
        status: EmailStatus,
        nextRetryAt: LocalDateTime?,
        errorMessage: String?,
        updatedAt: LocalDateTime
    ): Int

    /**
     * Delete old completed notifications
     */
    @Query("""
        DELETE FROM EmailNotificationLog e
        WHERE e.status IN ('SENT', 'PERMANENTLY_FAILED')
        AND e.updatedAt < :beforeDate
    """)
    fun deleteOldNotifications(beforeDate: LocalDateTime): Int

    /**
     * Find notifications by subject pattern
     */
    @Query("SELECT e FROM EmailNotificationLog e WHERE LOWER(e.subject) LIKE LOWER(CONCAT('%', :pattern, '%'))")
    fun findBySubjectContaining(pattern: String): List<EmailNotificationLog>

    /**
     * Get average delivery time for successful notifications
     */
    @Query("""
        SELECT AVG(TIMESTAMPDIFF(SECOND, e.createdAt, e.sentAt))
        FROM EmailNotificationLog e
        WHERE e.status = 'SENT'
        AND e.sentAt IS NOT NULL
        AND e.createdAt BETWEEN :startDate AND :endDate
    """)
    fun getAverageDeliveryTimeSeconds(startDate: LocalDateTime, endDate: LocalDateTime): Optional<Double>

    /**
     * Find top error messages
     */
    @Query("""
        SELECT new map(
            e.errorMessage as error,
            COUNT(e) as count
        )
        FROM EmailNotificationLog e
        WHERE e.errorMessage IS NOT NULL
        AND e.createdAt BETWEEN :startDate AND :endDate
        GROUP BY e.errorMessage
        ORDER BY COUNT(e) DESC
    """)
    fun getTopErrorMessages(startDate: LocalDateTime, endDate: LocalDateTime): List<Map<String, Any>>

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