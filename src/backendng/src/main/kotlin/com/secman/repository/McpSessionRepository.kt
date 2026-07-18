package com.secman.repository

import com.secman.domain.McpSession
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

    // ===== SESSION CLEANUP QUERIES =====

    /**
     * Find expired sessions based on last activity.
     * Used for automated session cleanup.
     */
    @Query("SELECT ms FROM McpSession ms WHERE ms.lastActivity < :cutoffTime ORDER BY ms.lastActivity ASC")
    fun findExpired(cutoffTime: LocalDateTime): List<McpSession>

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

    // ===== CLIENT INFORMATION QUERIES =====

    /**
     * Find sessions by client IP address.
     * Used for security monitoring and access tracking.
     */
    @Query("SELECT ms FROM McpSession ms WHERE ms.clientIp = :clientIp ORDER BY ms.createdAt DESC")
    fun findByClientIp(clientIp: String): List<McpSession>

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

    // ===== BATCH OPERATIONS =====

    /**
     * Deactivate all sessions for a specific API key.
     * Used when an API key is revoked or deactivated.
     */
    @Query("UPDATE McpSession ms SET ms.isActive = false WHERE ms.apiKeyId = :apiKeyId")
    fun deactivateAllForApiKey(apiKeyId: Long): Int

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

    // ===== CAPACITY MANAGEMENT QUERIES =====

    /**
     * Get current active session count.
     * Used for load monitoring and capacity management.
     */
    @Query("SELECT COUNT(ms) FROM McpSession ms WHERE ms.isActive = true")
    fun countActiveSessions(): Long
}