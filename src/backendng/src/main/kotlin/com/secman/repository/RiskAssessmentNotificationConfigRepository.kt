package com.secman.repository

import com.secman.domain.RiskAssessmentNotificationConfig
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime
import java.util.*

/**
 * Repository for RiskAssessmentNotificationConfig entities
 */
@Repository
interface RiskAssessmentNotificationConfigRepository : JpaRepository<RiskAssessmentNotificationConfig, Long> {

    /**
     * Find all active configurations
     */
    @Query("SELECT r FROM RiskAssessmentNotificationConfig r WHERE r.isActive = true ORDER BY r.name")
    fun findActiveConfigurations(): List<RiskAssessmentNotificationConfig>

    /**
     * Find configurations by active status
     */
    fun findByIsActive(isActive: Boolean): List<RiskAssessmentNotificationConfig>

    /**
     * Find configuration by name
     */
    fun findByName(name: String): Optional<RiskAssessmentNotificationConfig>

    /**
     * Find configurations by notification timing
     */
    fun findByNotificationTiming(timing: String): List<RiskAssessmentNotificationConfig>

    /**
     * Find configurations by notification frequency
     */
    fun findByNotificationFrequency(frequency: String): List<RiskAssessmentNotificationConfig>

    /**
     * Find configurations with immediate notification timing
     */
    @Query("SELECT r FROM RiskAssessmentNotificationConfig r WHERE r.notificationTiming = 'immediate' AND r.isActive = true")
    fun findImmediateNotificationConfigs(): List<RiskAssessmentNotificationConfig>

    /**
     * Find configurations for scheduled notifications
     */
    @Query("""
        SELECT r FROM RiskAssessmentNotificationConfig r
        WHERE r.notificationTiming IN ('daily', 'weekly', 'monthly')
        AND r.isActive = true
        ORDER BY r.notificationTiming, r.name
    """)
    fun findScheduledNotificationConfigs(): List<RiskAssessmentNotificationConfig>

    /**
     * Find configurations that include a specific email address
     */
    @Query("SELECT r FROM RiskAssessmentNotificationConfig r WHERE r.recipientEmails LIKE CONCAT('%', :email, '%')")
    fun findConfigsContainingEmail(email: String): List<RiskAssessmentNotificationConfig>

    /**
     * Find configurations with specific conditions
     */
    @Query("SELECT r FROM RiskAssessmentNotificationConfig r WHERE r.conditions IS NOT NULL AND r.conditions != ''")
    fun findConfigsWithConditions(): List<RiskAssessmentNotificationConfig>

    /**
     * Find configurations without conditions (match all)
     */
    @Query("SELECT r FROM RiskAssessmentNotificationConfig r WHERE r.conditions IS NULL OR r.conditions = ''")
    fun findConfigsWithoutConditions(): List<RiskAssessmentNotificationConfig>

    /**
     * Find configurations created within time period
     */
    @Query("SELECT r FROM RiskAssessmentNotificationConfig r WHERE r.createdAt BETWEEN :startDate AND :endDate")
    fun findCreatedBetween(startDate: LocalDateTime, endDate: LocalDateTime): List<RiskAssessmentNotificationConfig>

    /**
     * Find configurations updated recently
     */
    @Query("SELECT r FROM RiskAssessmentNotificationConfig r WHERE r.updatedAt >= :since ORDER BY r.updatedAt DESC")
    fun findRecentlyUpdated(since: LocalDateTime): List<RiskAssessmentNotificationConfig>

    /**
     * Search configurations by name (case insensitive)
     */
    @Query("SELECT r FROM RiskAssessmentNotificationConfig r WHERE LOWER(r.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    fun searchByName(name: String): List<RiskAssessmentNotificationConfig>

    /**
     * Count active configurations
     */
    fun countByIsActive(isActive: Boolean): Long

    /**
     * Count configurations by timing
     */
    fun countByNotificationTiming(timing: String): Long

    /**
     * Count configurations by frequency
     */
    fun countByNotificationFrequency(frequency: String): Long

    /**
     * Check if name exists (for unique validation)
     */
    fun existsByName(name: String): Boolean

    /**
     * Check if name exists excluding specific ID
     */
    @Query("SELECT COUNT(r) > 0 FROM RiskAssessmentNotificationConfig r WHERE r.name = :name AND r.id != :excludeId")
    fun existsByNameAndIdNot(name: String, excludeId: Long): Boolean

    /**
     * Update active status by ID
     */
    @Query("""
        UPDATE RiskAssessmentNotificationConfig r
        SET r.isActive = :isActive,
            r.updatedAt = :updatedAt
        WHERE r.id = :id
    """)
    fun updateActiveStatus(id: Long, isActive: Boolean, updatedAt: LocalDateTime): Int

    /**
     * Update notification timing for configuration
     */
    @Query("""
        UPDATE RiskAssessmentNotificationConfig r
        SET r.notificationTiming = :timing,
            r.updatedAt = :updatedAt
        WHERE r.id = :id
    """)
    fun updateNotificationTiming(id: Long, timing: String, updatedAt: LocalDateTime): Int

    /**
     * Update notification frequency for configuration
     */
    @Query("""
        UPDATE RiskAssessmentNotificationConfig r
        SET r.notificationFrequency = :frequency,
            r.updatedAt = :updatedAt
        WHERE r.id = :id
    """)
    fun updateNotificationFrequency(id: Long, frequency: String, updatedAt: LocalDateTime): Int

    /**
     * Get configuration statistics
     */
    @Query("""
        SELECT new map(
            'total' as metric,
            COUNT(r) as count
        )
        FROM RiskAssessmentNotificationConfig r
        UNION ALL
        SELECT new map(
            'active' as metric,
            COUNT(r) as count
        )
        FROM RiskAssessmentNotificationConfig r
        WHERE r.isActive = true
        UNION ALL
        SELECT new map(
            'immediate' as metric,
            COUNT(r) as count
        )
        FROM RiskAssessmentNotificationConfig r
        WHERE r.notificationTiming = 'immediate' AND r.isActive = true
    """)
    fun getConfigurationStatistics(): List<Map<String, Any>>

    /**
     * Get timing distribution statistics
     */
    @Query("""
        SELECT new map(
            r.notificationTiming as timing,
            COUNT(r) as count
        )
        FROM RiskAssessmentNotificationConfig r
        WHERE r.isActive = true
        GROUP BY r.notificationTiming
    """)
    fun getTimingDistribution(): List<Map<String, Any>>

    /**
     * Get frequency distribution statistics
     */
    @Query("""
        SELECT new map(
            r.notificationFrequency as frequency,
            COUNT(r) as count
        )
        FROM RiskAssessmentNotificationConfig r
        WHERE r.isActive = true
        GROUP BY r.notificationFrequency
    """)
    fun getFrequencyDistribution(): List<Map<String, Any>>

    /**
     * Find configurations due for scheduled notifications
     */
    @Query("""
        SELECT r FROM RiskAssessmentNotificationConfig r
        WHERE r.isActive = true
        AND r.notificationTiming != 'immediate'
        ORDER BY r.notificationTiming, r.updatedAt ASC
    """)
    fun findConfigsDueForScheduledNotification(): List<RiskAssessmentNotificationConfig>

    /**
     * Bulk update active status
     */
    @Query("""
        UPDATE RiskAssessmentNotificationConfig r
        SET r.isActive = :isActive,
            r.updatedAt = :updatedAt
        WHERE r.id IN :ids
    """)
    fun bulkUpdateActiveStatus(ids: List<Long>, isActive: Boolean, updatedAt: LocalDateTime): Int

    /**
     * Find configurations with duplicate names
     */
    @Query("""
        SELECT r.name
        FROM RiskAssessmentNotificationConfig r
        GROUP BY r.name
        HAVING COUNT(r) > 1
    """)
    fun findDuplicateNames(): List<String>

    /**
     * Delete inactive configurations older than specified date
     */
    @Query("""
        DELETE FROM RiskAssessmentNotificationConfig r
        WHERE r.isActive = false
        AND r.updatedAt < :beforeDate
    """)
    fun deleteInactiveConfigsOlderThan(beforeDate: LocalDateTime): Int

    /**
     * Find configurations ordered by usage priority
     */
    @Query("""
        SELECT r FROM RiskAssessmentNotificationConfig r
        WHERE r.isActive = true
        ORDER BY
            CASE r.notificationTiming
                WHEN 'immediate' THEN 1
                WHEN 'daily' THEN 2
                WHEN 'weekly' THEN 3
                WHEN 'monthly' THEN 4
                ELSE 5
            END,
            CASE r.notificationFrequency
                WHEN 'critical_only' THEN 1
                WHEN 'high_only' THEN 2
                WHEN 'medium_and_above' THEN 3
                WHEN 'all' THEN 4
                ELSE 5
            END,
            r.name
    """)
    fun findConfigsByPriority(): List<RiskAssessmentNotificationConfig>
}