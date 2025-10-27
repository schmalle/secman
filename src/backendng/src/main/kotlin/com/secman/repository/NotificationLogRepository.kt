package com.secman.repository

import com.secman.domain.NotificationLog
import com.secman.domain.NotificationType
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import java.time.Instant

/**
 * Repository for NotificationLog entity
 * Feature 035: Outdated Asset Notification System
 */
@Repository
interface NotificationLogRepository : JpaRepository<NotificationLog, Long> {
    /**
     * Find logs within a date range
     */
    fun findBySentAtBetween(startDate: Instant, endDate: Instant, pageable: Pageable): Page<NotificationLog>

    /**
     * Find logs by notification type
     */
    fun findByNotificationType(notificationType: NotificationType, pageable: Pageable): Page<NotificationLog>

    /**
     * Find logs by owner email
     */
    fun findByOwnerEmail(ownerEmail: String, pageable: Pageable): Page<NotificationLog>

    /**
     * Find logs by status
     */
    fun findByStatus(status: String, pageable: Pageable): Page<NotificationLog>

    /**
     * Find all logs with pagination and sorting
     */
    override fun findAll(pageable: Pageable): Page<NotificationLog>

    /**
     * Count logs by status for metrics
     */
    fun countByStatus(status: String): Long

    /**
     * Count logs by notification type for metrics
     */
    fun countByNotificationType(notificationType: NotificationType): Long
}
