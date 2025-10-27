package com.secman.service

import com.secman.domain.NotificationLog
import com.secman.domain.NotificationType
import com.secman.repository.NotificationLogRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Service for creating and managing notification audit logs
 * Feature 035: Outdated Asset Notification System
 */
@Singleton
class NotificationLogService(
    private val notificationLogRepository: NotificationLogRepository
) {
    private val logger = LoggerFactory.getLogger(NotificationLogService::class.java)

    /**
     * Create a log entry for a successful notification
     */
    fun logSuccess(
        assetId: Long?,
        assetName: String,
        ownerEmail: String,
        notificationType: NotificationType
    ): NotificationLog {
        val log = NotificationLog(
            assetId = assetId,
            assetName = assetName,
            ownerEmail = ownerEmail,
            notificationType = notificationType,
            sentAt = Instant.now(),
            status = "SENT",
            errorMessage = null
        )
        val saved = notificationLogRepository.save(log)
        logger.info("Logged successful notification: $notificationType to $ownerEmail for asset $assetName")
        return saved
    }

    /**
     * Create a log entry for a failed notification
     */
    fun logFailure(
        assetId: Long?,
        assetName: String,
        ownerEmail: String,
        notificationType: NotificationType,
        errorMessage: String
    ): NotificationLog {
        val log = NotificationLog(
            assetId = assetId,
            assetName = assetName,
            ownerEmail = ownerEmail,
            notificationType = notificationType,
            sentAt = Instant.now(),
            status = "FAILED",
            errorMessage = errorMessage.take(1024) // Truncate to field limit
        )
        val saved = notificationLogRepository.save(log)
        logger.error("Logged failed notification: $notificationType to $ownerEmail for asset $assetName - $errorMessage")
        return saved
    }

    /**
     * Get all logs with pagination and sorting
     */
    fun getAllLogs(pageable: Pageable): Page<NotificationLog> {
        return notificationLogRepository.findAll(pageable)
    }

    /**
     * Get logs filtered by date range
     */
    fun getLogsByDateRange(startDate: Instant, endDate: Instant, pageable: Pageable): Page<NotificationLog> {
        return notificationLogRepository.findBySentAtBetween(startDate, endDate, pageable)
    }

    /**
     * Get logs filtered by notification type
     */
    fun getLogsByType(notificationType: NotificationType, pageable: Pageable): Page<NotificationLog> {
        return notificationLogRepository.findByNotificationType(notificationType, pageable)
    }

    /**
     * Get logs filtered by owner email
     */
    fun getLogsByOwner(ownerEmail: String, pageable: Pageable): Page<NotificationLog> {
        return notificationLogRepository.findByOwnerEmail(ownerEmail, pageable)
    }

    /**
     * Get logs filtered by status
     */
    fun getLogsByStatus(status: String, pageable: Pageable): Page<NotificationLog> {
        return notificationLogRepository.findByStatus(status, pageable)
    }

    /**
     * Get statistics for ADMIN dashboard
     */
    fun getStatistics(): NotificationStatistics {
        return NotificationStatistics(
            totalSent = notificationLogRepository.countByStatus("SENT"),
            totalFailed = notificationLogRepository.countByStatus("FAILED"),
            totalPending = notificationLogRepository.countByStatus("PENDING"),
            level1Count = notificationLogRepository.countByNotificationType(NotificationType.OUTDATED_LEVEL1),
            level2Count = notificationLogRepository.countByNotificationType(NotificationType.OUTDATED_LEVEL2),
            newVulnCount = notificationLogRepository.countByNotificationType(NotificationType.NEW_VULNERABILITY)
        )
    }

    data class NotificationStatistics(
        val totalSent: Long,
        val totalFailed: Long,
        val totalPending: Long,
        val level1Count: Long,
        val level2Count: Long,
        val newVulnCount: Long
    )
}
