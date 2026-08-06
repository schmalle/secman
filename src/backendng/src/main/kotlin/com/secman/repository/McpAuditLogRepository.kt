package com.secman.repository

import com.secman.domain.McpAuditLog
import com.secman.domain.McpEventType
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import java.time.LocalDateTime

/**
 * Repository interface for McpAuditLog entity operations.
 *
 * Provides comprehensive data access methods for MCP audit logging including
 * security monitoring, compliance reporting, performance analysis, and forensic investigation.
 */
@Repository
interface McpAuditLogRepository : JpaRepository<McpAuditLog, Long> {

    // ===== SECURITY MONITORING QUERIES =====

    /**
     * Find all security-related events within a time range.
     * Used for security monitoring and threat detection.
     */
    @Query("""
        SELECT al FROM McpAuditLog al
        WHERE al.eventType IN ('AUTH_FAILURE', 'PERMISSION_DENIED', 'RATE_LIMITED', 'SESSION_EXPIRED', 'INVALID_REQUEST')
        AND al.timestamp BETWEEN :startTime AND :endTime
        ORDER BY al.timestamp DESC
    """)
    fun findSecurityEvents(startTime: LocalDateTime, endTime: LocalDateTime): List<McpAuditLog>

    /**
     * Find security events with pagination.
     * Used for security dashboard with large datasets.
     */
    @Query(value = """
        SELECT al FROM McpAuditLog al
        WHERE al.eventType IN ('AUTH_FAILURE', 'PERMISSION_DENIED', 'RATE_LIMITED', 'SESSION_EXPIRED', 'INVALID_REQUEST')
        AND al.timestamp BETWEEN :startTime AND :endTime
        ORDER BY al.timestamp DESC
    """,
    countQuery = """
        SELECT COUNT(al) FROM McpAuditLog al
        WHERE al.eventType IN ('AUTH_FAILURE', 'PERMISSION_DENIED', 'RATE_LIMITED', 'SESSION_EXPIRED', 'INVALID_REQUEST')
        AND al.timestamp BETWEEN :startTime AND :endTime
    """)
    fun findSecurityEvents(startTime: LocalDateTime, endTime: LocalDateTime, pageable: Pageable): Page<McpAuditLog>

    /**
     * Count failed authentication attempts from an IP within time window.
     * Used for rate limiting and attack detection.
     */
    @Query("""
        SELECT COUNT(al) FROM McpAuditLog al
        WHERE al.eventType = 'AUTH_FAILURE'
        AND al.clientIp = :clientIp
        AND al.timestamp >= :since
    """)
    fun countAuthFailuresByIp(clientIp: String, since: LocalDateTime): Long

    // ===== EVENT TYPE QUERIES =====

    /**
     * Find audit logs by event type within time range.
     * Used for analyzing specific types of events.
     */
    @Query("""
        SELECT al FROM McpAuditLog al
        WHERE al.eventType = :eventType
        AND al.timestamp BETWEEN :startTime AND :endTime
        ORDER BY al.timestamp DESC
    """)
    fun findByEventType(eventType: McpEventType, startTime: LocalDateTime, endTime: LocalDateTime): List<McpAuditLog>

    /**
     * Count events by type within time range.
     * Used for event statistics and monitoring dashboards.
     */
    @Query("""
        SELECT COUNT(al) FROM McpAuditLog al
        WHERE al.eventType = :eventType
        AND al.timestamp BETWEEN :startTime AND :endTime
    """)
    fun countByEventType(eventType: McpEventType, startTime: LocalDateTime, endTime: LocalDateTime): Long

    // ===== USER ACTIVITY QUERIES =====

    /**
     * Find all audit logs for a specific user.
     * Used for user activity monitoring and investigation.
     */
    @Query("""
        SELECT al FROM McpAuditLog al
        WHERE al.userId = :userId
        AND al.timestamp >= :since
        ORDER BY al.timestamp DESC
    """)
    fun findByUserId(userId: Long, since: LocalDateTime): List<McpAuditLog>

    /**
     * Find user activity with pagination.
     * Used for user activity reports in admin interface.
     */
    @Query(value = """
        SELECT al FROM McpAuditLog al
        WHERE al.userId = :userId
        ORDER BY al.timestamp DESC
    """,
    countQuery = """
        SELECT COUNT(al) FROM McpAuditLog al
        WHERE al.userId = :userId
    """)
    fun findByUserId(userId: Long, pageable: Pageable): Page<McpAuditLog>

    /**
     * Get user activity summary statistics.
     * Returns user ID, total events, success rate, last activity.
     */
    @Query("""
        SELECT
            al.userId,
            COUNT(al) as totalEvents,
            AVG(CASE WHEN al.success = true THEN 1.0 ELSE 0.0 END) as successRate,
            MAX(al.timestamp) as lastActivity
        FROM McpAuditLog al
        WHERE al.userId IS NOT NULL
        AND al.timestamp >= :since
        GROUP BY al.userId
        ORDER BY totalEvents DESC
    """)
    fun getUserActivitySummary(since: LocalDateTime): List<Array<Any>>

    // ===== API KEY MONITORING QUERIES =====

    /**
     * Get API key usage statistics.
     * Returns API key ID, event count, success rate, last used timestamp.
     */
    @Query("""
        SELECT
            al.apiKeyId,
            COUNT(al) as eventCount,
            AVG(CASE WHEN al.success = true THEN 1.0 ELSE 0.0 END) as successRate,
            MAX(al.timestamp) as lastUsed
        FROM McpAuditLog al
        WHERE al.apiKeyId IS NOT NULL
        AND al.timestamp >= :since
        GROUP BY al.apiKeyId
        ORDER BY eventCount DESC
    """)
    fun getApiKeyUsageStatistics(since: LocalDateTime): List<Array<Any>>

    // ===== SESSION ACTIVITY QUERIES =====

    // ===== TOOL USAGE QUERIES =====

    /**
     * Get tool usage statistics.
     * Returns tool name, call count, success rate, avg duration.
     */
    @Query("""
        SELECT
            al.toolName,
            COUNT(al) as callCount,
            AVG(CASE WHEN al.success = true THEN 1.0 ELSE 0.0 END) as successRate,
            AVG(al.durationMs) as avgDuration
        FROM McpAuditLog al
        WHERE al.eventType = 'TOOL_CALL'
        AND al.toolName IS NOT NULL
        AND al.timestamp >= :since
        GROUP BY al.toolName
        ORDER BY callCount DESC
    """)
    fun getToolUsageStatistics(since: LocalDateTime): List<Array<Any>>

    // ===== PERFORMANCE ANALYSIS QUERIES =====

    /**
     * Find slow operations (exceeding duration threshold).
     * Used for performance monitoring and optimization.
     */
    @Query("""
        SELECT al FROM McpAuditLog al
        WHERE al.durationMs > :thresholdMs
        AND al.timestamp >= :since
        ORDER BY al.durationMs DESC
    """)
    fun findSlowOperations(thresholdMs: Long, since: LocalDateTime): List<McpAuditLog>

    /**
     * Get performance statistics by operation type.
     * Returns operation, count, avg/min/max duration.
     */
    @Query("""
        SELECT
            al.operation,
            COUNT(al) as operationCount,
            AVG(al.durationMs) as avgDuration,
            MIN(al.durationMs) as minDuration,
            MAX(al.durationMs) as maxDuration
        FROM McpAuditLog al
        WHERE al.operation IS NOT NULL
        AND al.durationMs IS NOT NULL
        AND al.timestamp >= :since
        GROUP BY al.operation
        ORDER BY avgDuration DESC
    """)
    fun getPerformanceStatistics(since: LocalDateTime): List<Array<Any>>

    /**
     * Get hourly performance trends.
     * Returns hour, average duration, operation count.
     */
    @Query("""
        SELECT
            HOUR(al.timestamp) as hour,
            AVG(al.durationMs) as avgDuration,
            COUNT(al) as operationCount
        FROM McpAuditLog al
        WHERE al.durationMs IS NOT NULL
        AND al.timestamp >= :since
        GROUP BY HOUR(al.timestamp)
        ORDER BY hour
    """)
    fun getHourlyPerformanceTrends(since: LocalDateTime): List<Array<Any>>

    // ===== CLIENT ANALYSIS QUERIES =====

    /**
     * Find logs by client IP address.
     * Used for client activity monitoring and security analysis.
     */
    @Query("""
        SELECT al FROM McpAuditLog al
        WHERE al.clientIp = :clientIp
        AND al.timestamp >= :since
        ORDER BY al.timestamp DESC
    """)
    fun findByClientIp(clientIp: String, since: LocalDateTime): List<McpAuditLog>

    /**
     * Get client IP activity statistics.
     * Returns client IP, event count, unique users, last activity.
     */
    @Query("""
        SELECT
            al.clientIp,
            COUNT(al) as eventCount,
            COUNT(DISTINCT al.userId) as uniqueUsers,
            MAX(al.timestamp) as lastActivity
        FROM McpAuditLog al
        WHERE al.clientIp IS NOT NULL
        AND al.timestamp >= :since
        GROUP BY al.clientIp
        ORDER BY eventCount DESC
    """)
    fun getClientIpStatistics(since: LocalDateTime): List<Array<Any>>

    // ===== COMPLIANCE AND REPORTING QUERIES =====

    /**
     * Find audit logs within date range for compliance reporting.
     * Used for generating compliance reports and audit trails.
     */
    fun findByTimestampBetween(startTime: LocalDateTime, endTime: LocalDateTime): List<McpAuditLog>

    // ===== CLEANUP AND MAINTENANCE QUERIES =====

    /**
     * Delete old audit logs before specified date.
     * Used for data retention compliance and storage management.
     */
    @Query("DELETE FROM McpAuditLog al WHERE al.timestamp < :cutoffDate")
    fun deleteOlderThan(cutoffDate: LocalDateTime): Int

    /**
     * Count logs older than specified date.
     * Used for planning cleanup operations.
     */
    @Query("SELECT COUNT(al) FROM McpAuditLog al WHERE al.timestamp < :cutoffDate")
    fun countOlderThan(cutoffDate: LocalDateTime): Long

    // ===== CORRELATION QUERIES =====

    /**
     * Find recent logs for correlation analysis.
     * Used for investigating patterns and relationships.
     */
    @Query("""
        SELECT al FROM McpAuditLog al
        WHERE al.timestamp >= :since
        ORDER BY al.timestamp DESC
        LIMIT :limit
    """)
    fun findRecent(since: LocalDateTime, limit: Int): List<McpAuditLog>

}