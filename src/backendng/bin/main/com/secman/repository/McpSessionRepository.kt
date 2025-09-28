package com.secman.repository

import com.secman.domain.McpSession
import com.secman.domain.McpConnectionType
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime
import java.util.*

/**
 * Repository interface for McpSession entity operations.
 *
 * Provides data access methods for MCP session management including
 * session validation, cleanup, activity tracking, and connection monitoring.
 */
@Repository
interface McpSessionRepository : JpaRepository<McpSession, Long> {

    // ===== SESSION VALIDATION QUERIES =====

    /**
     * Find an active session by its session ID.
     * Used for session validation during MCP requests.
     */
    @Query("SELECT ms FROM McpSession ms WHERE ms.sessionId = :sessionId AND ms.isActive = true")
    fun findBySessionIdAndActive(sessionId: String): Optional<McpSession>

    /**
     * Find a valid session (active and not expired) by session ID.
     * Used for comprehensive session validation.
     */
    @Query("""
        SELECT ms FROM McpSession ms
        WHERE ms.sessionId = :sessionId
        AND ms.isActive = true
        AND ms.lastActivity > :cutoffTime
    """)
    fun findValidSession(sessionId: String, cutoffTime: LocalDateTime): Optional<McpSession>

    /**
     * Check if a session exists and is valid.
     * Used for quick session validation without loading full entity.
     */
    @Query("""
        SELECT COUNT(ms) > 0 FROM McpSession ms
        WHERE ms.sessionId = :sessionId
        AND ms.isActive = true
        AND ms.lastActivity > :cutoffTime
    """)
    fun isSessionValid(sessionId: String, cutoffTime: LocalDateTime): Boolean

    // ===== API KEY ASSOCIATION QUERIES =====

    /**
     * Find all active sessions for a specific API key.
     * Used for monitoring API key usage and enforcing session limits.
     */
    @Query("SELECT ms FROM McpSession ms WHERE ms.apiKeyId = :apiKeyId AND ms.isActive = true ORDER BY ms.createdAt DESC")
    fun findActiveByApiKey(apiKeyId: Long): List<McpSession>

    /**
     * Count active sessions for a specific API key.
     * Used for enforcing per-API-key session limits.
     */
    @Query("SELECT COUNT(ms) FROM McpSession ms WHERE ms.apiKeyId = :apiKeyId AND ms.isActive = true")
    fun countActiveByApiKey(apiKeyId: Long): Long

    /**
     * Find the most recent session for an API key.
     * Used for tracking last activity per API key.
     */
    @Query("""
        SELECT ms FROM McpSession ms
        WHERE ms.apiKeyId = :apiKeyId
        ORDER BY ms.lastActivity DESC
        LIMIT 1
    """)
    fun findMostRecentByApiKey(apiKeyId: Long): Optional<McpSession>

    // ===== SESSION CLEANUP QUERIES =====

    /**
     * Find expired sessions based on last activity.
     * Used for automated session cleanup.
     */
    @Query("SELECT ms FROM McpSession ms WHERE ms.lastActivity < :cutoffTime ORDER BY ms.lastActivity ASC")
    fun findExpired(cutoffTime: LocalDateTime): List<McpSession>

    /**
     * Find inactive sessions that should be cleaned up.
     * Used for removing old inactive sessions.
     */
    @Query("SELECT ms FROM McpSession ms WHERE ms.isActive = false AND ms.lastActivity < :cutoffTime")
    fun findInactiveOlderThan(cutoffTime: LocalDateTime): List<McpSession>

    /**
     * Deactivate expired sessions in bulk.
     * Used for automated maintenance operations.
     */
    @Query("UPDATE McpSession ms SET ms.isActive = false WHERE ms.lastActivity < :cutoffTime AND ms.isActive = true")
    fun deactivateExpired(cutoffTime: LocalDateTime): Int

    /**
     * Delete old inactive sessions.
     * Used for database cleanup of terminated sessions.
     */
    @Query("DELETE FROM McpSession ms WHERE ms.isActive = false AND ms.lastActivity < :cutoffTime")
    fun deleteInactiveOlderThan(cutoffTime: LocalDateTime): Int

    // ===== ACTIVITY TRACKING QUERIES =====

    /**
     * Update last activity timestamp for a session.
     * Called on each MCP request to track session activity.
     */
    @Query("UPDATE McpSession ms SET ms.lastActivity = :lastActivity WHERE ms.id = :id")
    fun updateLastActivity(id: Long, lastActivity: LocalDateTime): Int

    /**
     * Find sessions with recent activity.
     * Used for monitoring current system activity.
     */
    @Query("""
        SELECT ms FROM McpSession ms
        WHERE ms.lastActivity >= :since
        AND ms.isActive = true
        ORDER BY ms.lastActivity DESC
    """)
    fun findRecentlyActive(since: LocalDateTime): List<McpSession>

    /**
     * Find idle sessions (no activity for specified time).
     * Used for identifying sessions that might need cleanup.
     */
    @Query("""
        SELECT ms FROM McpSession ms
        WHERE ms.lastActivity < :idleCutoff
        AND ms.isActive = true
        ORDER BY ms.lastActivity ASC
    """)
    fun findIdleSessions(idleCutoff: LocalDateTime): List<McpSession>

    // ===== CONNECTION TYPE QUERIES =====

    /**
     * Find sessions by connection type.
     * Used for monitoring different types of MCP connections.
     */
    @Query("SELECT ms FROM McpSession ms WHERE ms.connectionType = :connectionType AND ms.isActive = true")
    fun findByConnectionType(connectionType: McpConnectionType): List<McpSession>

    /**
     * Count active sessions by connection type.
     * Used for capacity planning and monitoring.
     */
    @Query("SELECT COUNT(ms) FROM McpSession ms WHERE ms.connectionType = :connectionType AND ms.isActive = true")
    fun countByConnectionType(connectionType: McpConnectionType): Long

    /**
     * Find streaming sessions (SSE and WebSocket).
     * Used for monitoring real-time connections.
     */
    @Query("""
        SELECT ms FROM McpSession ms
        WHERE ms.connectionType IN ('SSE', 'WEBSOCKET')
        AND ms.isActive = true
        ORDER BY ms.createdAt DESC
    """)
    fun findStreamingSessions(): List<McpSession>

    // ===== STATISTICS AND MONITORING QUERIES =====

    /**
     * Get session statistics by connection type.
     * Returns connection type and count for monitoring.
     */
    @Query("""
        SELECT ms.connectionType, COUNT(ms)
        FROM McpSession ms
        WHERE ms.isActive = true
        GROUP BY ms.connectionType
        ORDER BY COUNT(ms) DESC
    """)
    fun getConnectionTypeStatistics(): List<Array<Any>>

    /**
     * Get session creation statistics by time period.
     * Returns creation date and count for trend analysis.
     */
    @Query("""
        SELECT DATE(ms.createdAt) as creationDate, COUNT(ms)
        FROM McpSession ms
        WHERE ms.createdAt >= :since
        GROUP BY DATE(ms.createdAt)
        ORDER BY creationDate DESC
    """)
    fun getCreationStatistics(since: LocalDateTime): List<Array<Any>>

    /**
     * Get session duration statistics.
     * Returns average, min, max session durations.
     */
    @Query("""
        SELECT
            AVG(TIMESTAMPDIFF(MINUTE, ms.createdAt, ms.lastActivity)) as avgDuration,
            MIN(TIMESTAMPDIFF(MINUTE, ms.createdAt, ms.lastActivity)) as minDuration,
            MAX(TIMESTAMPDIFF(MINUTE, ms.createdAt, ms.lastActivity)) as maxDuration
        FROM McpSession ms
        WHERE ms.isActive = false
    """)
    fun getDurationStatistics(): List<Array<Any>>

    // ===== CLIENT INFORMATION QUERIES =====

    /**
     * Find sessions by client IP address.
     * Used for security monitoring and access tracking.
     */
    @Query("SELECT ms FROM McpSession ms WHERE ms.clientIp = :clientIp ORDER BY ms.createdAt DESC")
    fun findByClientIp(clientIp: String): List<McpSession>

    /**
     * Find sessions by user agent pattern.
     * Used for client compatibility monitoring.
     */
    @Query("SELECT ms FROM McpSession ms WHERE ms.userAgent LIKE :userAgentPattern")
    fun findByUserAgentPattern(userAgentPattern: String): List<McpSession>

    /**
     * Get unique client IPs with session counts.
     * Used for access pattern analysis.
     */
    @Query("""
        SELECT ms.clientIp, COUNT(ms)
        FROM McpSession ms
        WHERE ms.clientIp IS NOT NULL
        AND ms.createdAt >= :since
        GROUP BY ms.clientIp
        ORDER BY COUNT(ms) DESC
    """)
    fun getClientIpStatistics(since: LocalDateTime): List<Array<Any>>

    // ===== SECURITY MONITORING QUERIES =====

    /**
     * Find sessions from suspicious sources.
     * Used for security monitoring and threat detection.
     */
    @Query("""
        SELECT ms FROM McpSession ms
        WHERE ms.clientIp IN :suspiciousIps
        OR ms.userAgent LIKE '%bot%'
        ORDER BY ms.createdAt DESC
    """)
    fun findSuspiciousSessions(suspiciousIps: List<String>): List<McpSession>

    /**
     * Find sessions with unusual activity patterns.
     * Used for detecting potential abuse or attacks.
     */
    @Query("""
        SELECT ms FROM McpSession ms
        WHERE (
            TIMESTAMPDIFF(MINUTE, ms.createdAt, ms.lastActivity) > :maxDurationMinutes
            OR TIMESTAMPDIFF(SECOND, ms.createdAt, ms.lastActivity) < :minDurationSeconds
        )
        AND ms.createdAt >= :since
        ORDER BY ms.createdAt DESC
    """)
    fun findAnomalousSessions(
        maxDurationMinutes: Int,
        minDurationSeconds: Int,
        since: LocalDateTime
    ): List<McpSession>

    // ===== BATCH OPERATIONS =====

    /**
     * Deactivate all sessions for a specific API key.
     * Used when an API key is revoked or deactivated.
     */
    @Query("UPDATE McpSession ms SET ms.isActive = false WHERE ms.apiKeyId = :apiKeyId")
    fun deactivateAllForApiKey(apiKeyId: Long): Int

    /**
     * Close sessions of a specific connection type.
     * Used for maintenance or emergency shutdown of specific connection types.
     */
    @Query("UPDATE McpSession ms SET ms.isActive = false WHERE ms.connectionType = :connectionType")
    fun closeSessionsByConnectionType(connectionType: McpConnectionType): Int

    // ===== CUSTOM FINDER METHODS =====

    /**
     * Find sessions created between specific dates.
     * Used for audit and compliance reporting.
     */
    fun findByCreatedAtBetween(startDate: LocalDateTime, endDate: LocalDateTime): List<McpSession>

    /**
     * Check if any session exists with the given session ID.
     * Used for ensuring global session ID uniqueness.
     */
    fun existsBySessionId(sessionId: String): Boolean

    /**
     * Count sessions created since a specific time.
     * Used for monitoring system load and activity.
     */
    fun countByCreatedAtGreaterThan(since: LocalDateTime): Long

    // ===== CAPACITY MANAGEMENT QUERIES =====

    /**
     * Get current active session count.
     * Used for load monitoring and capacity management.
     */
    @Query("SELECT COUNT(ms) FROM McpSession ms WHERE ms.isActive = true")
    fun countActiveSessions(): Long

    /**
     * Get active session count by hour for trend analysis.
     * Used for capacity planning and load prediction.
     */
    @Query("""
        SELECT HOUR(ms.createdAt) as hour, COUNT(ms)
        FROM McpSession ms
        WHERE ms.createdAt >= :since
        AND ms.isActive = true
        GROUP BY HOUR(ms.createdAt)
        ORDER BY hour
    """)
    fun getSessionCountByHour(since: LocalDateTime): List<Array<Any>>

    /**
     * Find sessions exceeding normal duration thresholds.
     * Used for identifying long-running sessions that may need attention.
     */
    @Query("""
        SELECT ms FROM McpSession ms
        WHERE ms.isActive = true
        AND TIMESTAMPDIFF(MINUTE, ms.createdAt, :now) > :thresholdMinutes
        ORDER BY ms.createdAt ASC
    """)
    fun findLongRunningSessions(now: LocalDateTime, thresholdMinutes: Int): List<McpSession>
}