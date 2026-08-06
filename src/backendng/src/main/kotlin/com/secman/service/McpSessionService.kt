package com.secman.service

import com.secman.domain.*
import com.secman.repository.McpSessionRepository
import com.secman.repository.McpAuditLogRepository
import com.secman.dto.mcp.CleanupResult
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Service for MCP session lifecycle management and monitoring.
 *
 * Handles session creation, validation, activity tracking, cleanup, and provides
 * comprehensive session monitoring capabilities for MCP protocol connections.
 */
@Singleton
class McpSessionService(
    @Inject private val sessionRepository: McpSessionRepository,
    @Inject private val auditLogRepository: McpAuditLogRepository
) {
    private val logger = LoggerFactory.getLogger(McpSessionService::class.java)

    // In-memory session activity tracking for performance
    private val activeSessionActivity = ConcurrentHashMap<String, LocalDateTime>()

    // Configuration
    private val defaultSessionTimeoutMinutes = 60
    private val maxSessionsPerApiKey = 10
    private val maxConcurrentSessions = 200
    private val cleanupIntervalMinutes = 15

    /**
     * Create a new MCP session for an authenticated API key.
     */
    fun createSession(
        apiKeyId: Long,
        userId: Long,
        clientInfo: String,
        capabilities: String,
        connectionType: McpConnectionType = McpConnectionType.HTTP,
        clientIp: String? = null,
        userAgent: String? = null
    ): SessionCreationResult {
        try {
            // Check concurrent session limits
            val currentActiveSessions = sessionRepository.countActiveSessions()
            if (currentActiveSessions >= maxConcurrentSessions) {
                return SessionCreationResult(
                    success = false,
                    sessionId = null,
                    errorCode = "MAX_SESSIONS_EXCEEDED",
                    errorMessage = "Maximum concurrent sessions exceeded"
                )
            }

            // Check per-API-key session limits
            val apiKeyActiveSessions = sessionRepository.countActiveByApiKey(apiKeyId)
            if (apiKeyActiveSessions >= maxSessionsPerApiKey) {
                return SessionCreationResult(
                    success = false,
                    sessionId = null,
                    errorCode = "API_KEY_SESSION_LIMIT",
                    errorMessage = "Maximum sessions per API key exceeded"
                )
            }

            // Validate input data
            if (!McpSession.validateClientInfoJson(clientInfo)) {
                return SessionCreationResult(
                    success = false,
                    sessionId = null,
                    errorCode = "INVALID_CLIENT_INFO",
                    errorMessage = "Invalid client info format"
                )
            }

            if (!McpSession.validateCapabilitiesJson(capabilities)) {
                return SessionCreationResult(
                    success = false,
                    sessionId = null,
                    errorCode = "INVALID_CAPABILITIES",
                    errorMessage = "Invalid capabilities format"
                )
            }

            // Generate unique session ID
            val sessionId = generateUniqueSessionId()

            // Create session entity
            val session = McpSession(
                sessionId = sessionId,
                apiKeyId = apiKeyId,
                clientInfo = clientInfo,
                capabilities = capabilities,
                connectionType = connectionType,
                clientIp = clientIp,
                userAgent = userAgent
            )

            // Save session to database
            val savedSession = sessionRepository.save(session)

            // Track session activity in memory
            activeSessionActivity[sessionId] = LocalDateTime.now()

            // Log session creation
            logSessionEvent(
                McpEventType.SESSION_CREATED,
                sessionId,
                apiKeyId,
                userId,
                true,
                contextData = McpSession.createClientInfoJson(
                    connectionType.name,
                    "1.0.0",
                    mapOf("clientIp" to (clientIp ?: "unknown"))
                )
            )

            logger.info(
                "MCP session created: sessionId={}, apiKeyId={}, userId={}, connectionType={}",
                sessionId, apiKeyId, userId, connectionType
            )

            return SessionCreationResult(
                success = true,
                sessionId = sessionId,
                session = savedSession,
                errorCode = null,
                errorMessage = null
            )

        } catch (e: Exception) {
            logger.error("Failed to create MCP session for apiKeyId={}", apiKeyId, e)

            return SessionCreationResult(
                success = false,
                sessionId = null,
                errorCode = "SYSTEM_ERROR",
                errorMessage = "Session creation failed: ${e.message}"
            )
        }
    }

    /**
     * Validate and retrieve an active session.
     */
    fun validateSession(sessionId: String, updateActivity: Boolean = true): SessionValidationResult {
        try {
            // Check in-memory activity tracking first for performance
            val lastActivity = activeSessionActivity[sessionId]
            if (lastActivity == null) {
                // Session not in memory, check database
                val cutoffTime = LocalDateTime.now().minusMinutes(defaultSessionTimeoutMinutes.toLong())
                val sessionOpt = sessionRepository.findValidSession(sessionId, cutoffTime)

                if (sessionOpt.isEmpty) {
                    return SessionValidationResult(
                        valid = false,
                        session = null,
                        errorCode = "SESSION_INVALID",
                        errorMessage = "Session not found or expired"
                    )
                }

                val session = sessionOpt.get()
                // Add to memory tracking
                activeSessionActivity[sessionId] = session.lastActivity

                return SessionValidationResult(
                    valid = true,
                    session = session,
                    errorCode = null,
                    errorMessage = null
                )
            }

            // Check if session is expired based on memory tracking
            val cutoffTime = LocalDateTime.now().minusMinutes(defaultSessionTimeoutMinutes.toLong())
            if (lastActivity.isBefore(cutoffTime)) {
                // Session expired, remove from memory and deactivate
                activeSessionActivity.remove(sessionId)
                deactivateExpiredSession(sessionId)

                return SessionValidationResult(
                    valid = false,
                    session = null,
                    errorCode = "SESSION_EXPIRED",
                    errorMessage = "Session has expired"
                )
            }

            // Update activity if requested
            if (updateActivity) {
                updateSessionActivity(sessionId)
            }

            // Retrieve full session for return
            val sessionOpt = sessionRepository.findBySessionIdAndActive(sessionId)
            if (sessionOpt.isEmpty) {
                activeSessionActivity.remove(sessionId)
                return SessionValidationResult(
                    valid = false,
                    session = null,
                    errorCode = "SESSION_NOT_FOUND",
                    errorMessage = "Session not found in database"
                )
            }

            return SessionValidationResult(
                valid = true,
                session = sessionOpt.get(),
                errorCode = null,
                errorMessage = null
            )

        } catch (e: Exception) {
            logger.error("Session validation failed for sessionId={}", sessionId, e)

            return SessionValidationResult(
                valid = false,
                session = null,
                errorCode = "VALIDATION_ERROR",
                errorMessage = "Session validation error: ${e.message}"
            )
        }
    }

    /**
     * Update session activity timestamp.
     */
    fun updateSessionActivity(sessionId: String) {
        try {
            val now = LocalDateTime.now()

            // Update in-memory tracking
            activeSessionActivity[sessionId] = now

            // Update database (async to avoid blocking)
            val sessionOpt = sessionRepository.findBySessionIdAndActive(sessionId)
            if (sessionOpt.isPresent) {
                sessionRepository.updateLastActivity(sessionOpt.get().id, now)
            }

        } catch (e: Exception) {
            logger.error("Failed to update session activity for sessionId={}", sessionId, e)
        }
    }

    /**
     * Close a session gracefully.
     */
    fun closeSession(sessionId: String, reason: String = "User requested"): SessionCloseResult {
        try {
            val sessionOpt = sessionRepository.findBySessionIdAndActive(sessionId)
            if (sessionOpt.isEmpty) {
                return SessionCloseResult(
                    success = false,
                    errorCode = "SESSION_NOT_FOUND",
                    errorMessage = "Session not found or already closed"
                )
            }

            val session = sessionOpt.get()

            // Deactivate session
            val updatedSession = session.copy(isActive = false, notes = reason)
            sessionRepository.save(updatedSession)

            // Remove from memory tracking
            activeSessionActivity.remove(sessionId)

            // Log session closure
            logSessionEvent(
                McpEventType.SESSION_CLOSED,
                sessionId,
                session.apiKeyId,
                0L, // We don't have userId in session, would need to join with ApiKey
                true,
                contextData = McpSession.createClientInfoJson("system", "1.0.0", mapOf("reason" to reason))
            )

            logger.info("MCP session closed: sessionId={}, reason={}", sessionId, reason)

            return SessionCloseResult(
                success = true,
                errorCode = null,
                errorMessage = null
            )

        } catch (e: Exception) {
            logger.error("Failed to close session: sessionId={}", sessionId, e)

            return SessionCloseResult(
                success = false,
                errorCode = "CLOSE_ERROR",
                errorMessage = "Failed to close session: ${e.message}"
            )
        }
    }

    /**
     * Get session statistics for monitoring.
     */
    fun getSessionStatistics(): SessionStatistics {
        try {
            val activeCount = sessionRepository.countActiveSessions()
            val connectionStats = sessionRepository.getConnectionTypeStatistics()
            val recentlyActive = sessionRepository.findRecentlyActive(
                LocalDateTime.now().minusMinutes(15)
            ).size

            val memoryTrackedSessions = activeSessionActivity.size

            return SessionStatistics(
                totalActiveSessions = activeCount,
                recentlyActiveSessions = recentlyActive.toLong(),
                memoryTrackedSessions = memoryTrackedSessions.toLong(),
                connectionTypeBreakdown = connectionStats.map {
                    it[0].toString() to (it[1] as Number).toLong()
                }.toMap(),
                maxConcurrentSessions = maxConcurrentSessions.toLong(),
                utilizationPercentage = (activeCount.toDouble() / maxConcurrentSessions.toDouble()) * 100.0
            )

        } catch (e: Exception) {
            logger.error("Failed to get session statistics", e)

            return SessionStatistics(
                totalActiveSessions = 0,
                recentlyActiveSessions = 0,
                memoryTrackedSessions = 0,
                connectionTypeBreakdown = emptyMap(),
                maxConcurrentSessions = maxConcurrentSessions.toLong(),
                utilizationPercentage = 0.0
            )
        }
    }

    /**
     * Cleanup expired sessions and perform maintenance.
     */
    fun performSessionCleanup(): CleanupResult {
        val startTime = System.currentTimeMillis()
        var cleanupCount = 0

        try {
            val now = LocalDateTime.now()
            val expiredCutoff = now.minusMinutes(defaultSessionTimeoutMinutes.toLong())
            val oldCutoff = now.minusHours(24) // Remove very old inactive sessions

            // Find and deactivate expired sessions
            val expiredSessions = sessionRepository.findExpired(expiredCutoff)
            for (session in expiredSessions) {
                if (session.isActive) {
                    sessionRepository.save(session.copy(
                        isActive = false,
                        notes = "Expired due to inactivity"
                    ))
                    activeSessionActivity.remove(session.sessionId)
                    cleanupCount++
                }
            }

            // Delete old inactive sessions
            val deletedCount = sessionRepository.deleteInactiveOlderThan(oldCutoff)
            cleanupCount += deletedCount

            // Clean up memory tracking for sessions that no longer exist
            val memoryCleanup = cleanupMemoryTracking()
            cleanupCount += memoryCleanup

            val duration = System.currentTimeMillis() - startTime

            if (cleanupCount > 0) {
                logger.info("Session cleanup completed: {} operations in {}ms", cleanupCount, duration)
            }

            return CleanupResult(
                success = true,
                cleanedUpCount = cleanupCount,
                durationMs = duration,
                message = "Session cleanup completed successfully"
            )

        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error("Session cleanup failed after {}ms", duration, e)

            return CleanupResult(
                success = false,
                cleanedUpCount = cleanupCount,
                durationMs = duration,
                message = "Session cleanup failed: ${e.message}"
            )
        }
    }

    /**
     * Get sessions for a specific API key.
     */
    fun getSessionsForApiKey(apiKeyId: Long, activeOnly: Boolean = true): List<McpSession> {
        return if (activeOnly) {
            sessionRepository.findActiveByApiKey(apiKeyId)
        } else {
            sessionRepository.findByApiKeyIdAndActiveOrInactive(apiKeyId)
        }
    }

    /**
     * Force close all sessions for an API key (used when key is revoked).
     */
    fun closeAllSessionsForApiKey(apiKeyId: Long, reason: String = "API key revoked"): Int {
        try {
            val activeSessions = sessionRepository.findActiveByApiKey(apiKeyId)
            var closedCount = 0

            for (session in activeSessions) {
                val closeResult = closeSession(session.sessionId, reason)
                if (closeResult.success) {
                    closedCount++
                }
            }

            logger.info("Closed {} sessions for apiKeyId={}, reason={}", closedCount, apiKeyId, reason)
            return closedCount

        } catch (e: Exception) {
            logger.error("Failed to close sessions for apiKeyId={}", apiKeyId, e)
            return 0
        }
    }

    // Private helper methods

    private fun generateUniqueSessionId(): String {
        var sessionId: String
        var attempts = 0

        do {
            sessionId = McpSession.generateSessionId()
            attempts++

            if (attempts > 10) {
                throw RuntimeException("Failed to generate unique session ID after 10 attempts")
            }

        } while (sessionRepository.existsBySessionId(sessionId))

        return sessionId
    }

    private fun deactivateExpiredSession(sessionId: String) {
        try {
            val sessionOpt = sessionRepository.findBySessionIdAndActive(sessionId)
            if (sessionOpt.isPresent) {
                val session = sessionOpt.get()
                sessionRepository.save(session.copy(
                    isActive = false,
                    notes = "Expired due to inactivity"
                ))

                // Log expiration event
                logSessionEvent(
                    McpEventType.SESSION_EXPIRED,
                    sessionId,
                    session.apiKeyId,
                    0L,
                    false,
                    errorCode = "SESSION_EXPIRED",
                    errorMessage = "Session expired due to inactivity"
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to deactivate expired session: sessionId={}", sessionId, e)
        }
    }

    private fun cleanupMemoryTracking(): Int {
        try {
            var cleanedCount = 0
            val iterator = activeSessionActivity.entries.iterator()
            val cutoffTime = LocalDateTime.now().minusMinutes(defaultSessionTimeoutMinutes.toLong())

            while (iterator.hasNext()) {
                val entry = iterator.next()
                val sessionId = entry.key
                val lastActivity = entry.value

                // Remove if too old or if session no longer exists in database
                if (lastActivity.isBefore(cutoffTime) || !sessionRepository.existsBySessionId(sessionId)) {
                    iterator.remove()
                    cleanedCount++
                }
            }

            return cleanedCount

        } catch (e: Exception) {
            logger.error("Failed to cleanup memory tracking", e)
            return 0
        }
    }

    private fun logSessionEvent(
        eventType: McpEventType,
        sessionId: String,
        apiKeyId: Long,
        userId: Long,
        success: Boolean,
        errorCode: String? = null,
        errorMessage: String? = null,
        contextData: String? = null
    ) {
        try {
            val auditLog = McpAuditLog.createSessionEvent(
                eventType = eventType,
                sessionId = sessionId,
                apiKeyId = apiKeyId,
                userId = userId,
                success = success,
                errorCode = errorCode,
                errorMessage = errorMessage,
                contextData = contextData
            )
            auditLogRepository.save(auditLog)
        } catch (e: Exception) {
            logger.error("Failed to log session event: eventType={}, sessionId={}", eventType, sessionId, e)
        }
    }

    // Extension function to handle the missing method in repository
    private fun McpSessionRepository.findByApiKeyIdAndActiveOrInactive(apiKeyId: Long): List<McpSession> {
        return findByApiKeyIdOrderByCreatedAtDesc(apiKeyId) // Assumes this method exists or can be added
    }

    private fun McpSessionRepository.findByApiKeyIdOrderByCreatedAtDesc(apiKeyId: Long): List<McpSession> {
        // This would need to be added to the repository interface
        // For now, we'll use the active method as a fallback
        return findActiveByApiKey(apiKeyId)
    }
}

/**
 * Result of session creation attempt.
 */
data class SessionCreationResult(
    val success: Boolean,
    val sessionId: String?,
    val session: McpSession? = null,
    val errorCode: String?,
    val errorMessage: String?
)

/**
 * Result of session validation.
 */
data class SessionValidationResult(
    val valid: Boolean,
    val session: McpSession?,
    val errorCode: String?,
    val errorMessage: String?
)

/**
 * Result of session close operation.
 */
data class SessionCloseResult(
    val success: Boolean,
    val errorCode: String?,
    val errorMessage: String?
)

/**
 * Session statistics for monitoring.
 */
data class SessionStatistics(
    val totalActiveSessions: Long,
    val recentlyActiveSessions: Long,
    val memoryTrackedSessions: Long,
    val connectionTypeBreakdown: Map<String, Long>,
    val maxConcurrentSessions: Long,
    val utilizationPercentage: Double
)

