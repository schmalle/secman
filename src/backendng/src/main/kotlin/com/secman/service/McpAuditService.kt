package com.secman.service

import com.secman.domain.*
import com.secman.repository.McpAuditLogRepository
import com.secman.dto.mcp.CleanupResult
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import org.slf4j.LoggerFactory

/**
 * Service for comprehensive MCP audit logging and security monitoring.
 *
 * Provides centralized audit logging, security event analysis, compliance reporting,
 * and real-time monitoring capabilities for all MCP operations.
 */
@Singleton
class McpAuditService(
    @Inject private val auditLogRepository: McpAuditLogRepository
) {
    private val logger = LoggerFactory.getLogger(McpAuditService::class.java)

    /**
     * Log a tool call execution with comprehensive details.
     *
     * @param delegatedUserEmail Email of the user on whose behalf the request was made (Feature: 050-mcp-user-delegation)
     * @param delegatedUserId ID of the delegated user (Feature: 050-mcp-user-delegation)
     */
    fun logToolCall(
        apiKeyId: Long,
        userId: Long,
        sessionId: String,
        toolName: String,
        operation: McpOperation,
        arguments: Map<String, Any>,
        success: Boolean,
        durationMs: Long? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
        requestId: String? = null,
        requestSize: Long? = null,
        responseSize: Long? = null,
        resultSize: Int? = null,
        cacheHit: Boolean? = null,
        delegatedUserEmail: String? = null,
        delegatedUserId: Long? = null
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                val contextData = McpAuditLog.createToolCallContext(
                    arguments = arguments,
                    resultSize = resultSize,
                    cacheHit = cacheHit
                )

                val auditLog = McpAuditLog(
                    eventType = McpEventType.TOOL_CALL,
                    apiKeyId = apiKeyId,
                    userId = userId,
                    sessionId = sessionId,
                    operation = operation,
                    toolName = toolName,
                    success = success,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                    contextData = contextData,
                    requestId = requestId,
                    durationMs = durationMs,
                    requestSizeBytes = requestSize,
                    responseSizeBytes = responseSize,
                    severity = if (success) AuditSeverity.INFO else AuditSeverity.ERROR,
                    delegatedUserEmail = delegatedUserEmail,
                    delegatedUserId = delegatedUserId
                )

                auditLogRepository.save(auditLog)

                if (!success) {
                    logger.warn(
                        "Tool call failed: toolName={}, error={}, sessionId={}, delegatedUser={}",
                        toolName, errorCode, sessionId, delegatedUserEmail
                    )
                }

            } catch (e: Exception) {
                logger.error("Failed to log tool call audit: toolName={}, sessionId={}", toolName, sessionId, e)
            }
        }
    }

    /**
     * Log authentication events (success/failure).
     */
    fun logAuthenticationEvent(
        eventType: McpEventType,
        apiKeyId: Long?,
        userId: Long?,
        sessionId: String? = null,
        success: Boolean,
        errorCode: String? = null,
        errorMessage: String? = null,
        clientIp: String? = null,
        userAgent: String? = null,
        requestId: String? = null
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                val auditLog = if (success && apiKeyId != null && userId != null) {
                    McpAuditLog.createAuthSuccess(
                        apiKeyId = apiKeyId,
                        userId = userId,
                        sessionId = sessionId,
                        clientIp = clientIp,
                        userAgent = userAgent,
                        requestId = requestId
                    )
                } else {
                    McpAuditLog.createAuthFailure(
                        apiKeyId = apiKeyId,
                        errorCode = errorCode ?: "UNKNOWN_ERROR",
                        errorMessage = errorMessage ?: "Authentication failed",
                        clientIp = clientIp,
                        userAgent = userAgent,
                        requestId = requestId
                    )
                }

                auditLogRepository.save(auditLog)

                if (!success) {
                    logger.warn(
                        "Authentication failed: errorCode={}, clientIp={}, apiKeyId={}",
                        errorCode, clientIp, apiKeyId
                    )
                }

            } catch (e: Exception) {
                logger.error("Failed to log authentication event: eventType={}, success={}", eventType, success, e)
            }
        }
    }

    /**
     * Log session lifecycle events.
     */
    fun logSessionEvent(
        eventType: McpEventType,
        sessionId: String,
        apiKeyId: Long,
        userId: Long,
        success: Boolean,
        errorCode: String? = null,
        errorMessage: String? = null,
        contextData: String? = null
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
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

                logger.debug("Session event logged: eventType={}, sessionId={}, success={}", eventType, sessionId, success)

            } catch (e: Exception) {
                logger.error("Failed to log session event: eventType={}, sessionId={}", eventType, sessionId, e)
            }
        }
    }

    /**
     * Get security events for monitoring dashboard.
     */
    fun getSecurityEvents(
        startTime: LocalDateTime = LocalDateTime.now().minusHours(24),
        endTime: LocalDateTime = LocalDateTime.now(),
        severityFilter: AuditSeverity? = null
    ): SecurityEventSummary {
        try {
            val securityEvents = auditLogRepository.findSecurityEvents(startTime, endTime)

            val filteredEvents = if (severityFilter != null) {
                securityEvents.filter { it.severity == severityFilter }
            } else {
                securityEvents
            }

            val eventsByType = filteredEvents.groupBy { it.eventType }
            val eventsByIp = filteredEvents.filter { it.clientIp != null }
                .groupBy { it.clientIp!! }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }
                .take(10)

            val highRiskEvents = filteredEvents.filter { it.severity == AuditSeverity.ERROR }.size
            val totalEvents = filteredEvents.size

            return SecurityEventSummary(
                totalEvents = totalEvents,
                highRiskEvents = highRiskEvents,
                eventsByType = eventsByType.mapValues { it.value.size },
                topSourceIps = eventsByIp.toMap(),
                timeRange = Pair(startTime, endTime),
                events = filteredEvents.take(100) // Limit for performance
            )

        } catch (e: Exception) {
            logger.error("Failed to get security events", e)
            return SecurityEventSummary(
                totalEvents = 0,
                highRiskEvents = 0,
                eventsByType = emptyMap(),
                topSourceIps = emptyMap(),
                timeRange = Pair(startTime, endTime),
                events = emptyList()
            )
        }
    }

    /**
     * Generate compliance report for audit purposes.
     */
    fun generateComplianceReport(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        includeUserActivity: Boolean = true,
        includeSecurityEvents: Boolean = true,
        includePerformanceMetrics: Boolean = false
    ): ComplianceReport {
        try {
            val allEvents = auditLogRepository.findByTimestampBetween(startDate, endDate)
            val totalEvents = allEvents.size

            // User activity summary
            val userActivitySummary = if (includeUserActivity) {
                auditLogRepository.getUserActivitySummary(startDate).map {
                    UserActivitySummary(
                        userId = (it[0] as Number).toLong(),
                        totalEvents = (it[1] as Number).toLong(),
                        successRate = (it[2] as Number).toDouble(),
                        lastActivity = it[3] as LocalDateTime
                    )
                }
            } else emptyList()

            // Security events
            val securityEventCount = if (includeSecurityEvents) {
                auditLogRepository.findSecurityEvents(startDate, endDate).size
            } else 0

            // API key usage statistics
            val apiKeyStats = auditLogRepository.getApiKeyUsageStatistics(startDate).map {
                ApiKeyUsageSummary(
                    apiKeyId = (it[0] as Number).toLong(),
                    eventCount = (it[1] as Number).toLong(),
                    successRate = (it[2] as Number).toDouble(),
                    lastUsed = it[3] as LocalDateTime
                )
            }

            // Tool usage statistics
            val toolStats = auditLogRepository.getToolUsageStatistics(startDate).map {
                ToolUsageSummary(
                    toolName = it[0] as String,
                    callCount = (it[1] as Number).toLong(),
                    successRate = (it[2] as Number).toDouble(),
                    avgDuration = (it[3] as? Number)?.toLong()
                )
            }

            // Performance metrics
            val performanceMetrics = if (includePerformanceMetrics) {
                auditLogRepository.getPerformanceStatistics(startDate).map {
                    PerformanceMetric(
                        operation = it[0] as McpOperation,
                        operationCount = (it[1] as Number).toLong(),
                        avgDuration = (it[2] as Number).toLong(),
                        minDuration = (it[3] as Number).toLong(),
                        maxDuration = (it[4] as Number).toLong()
                    )
                }
            } else emptyList()

            return ComplianceReport(
                reportPeriod = Pair(startDate, endDate),
                generatedAt = LocalDateTime.now(),
                totalEvents = totalEvents.toLong(),
                securityEventCount = securityEventCount.toLong(),
                userActivitySummaries = userActivitySummary,
                apiKeyUsageSummaries = apiKeyStats,
                toolUsageSummaries = toolStats,
                performanceMetrics = performanceMetrics
            )

        } catch (e: Exception) {
            logger.error("Failed to generate compliance report", e)
            throw RuntimeException("Compliance report generation failed: ${e.message}")
        }
    }

    /**
     * Analyze user behavior patterns for security monitoring.
     */
    fun analyzeUserBehavior(
        userId: Long,
        lookbackDays: Int = 30
    ): UserBehaviorAnalysis {
        try {
            val since = LocalDateTime.now().minusDays(lookbackDays.toLong())
            val userEvents = auditLogRepository.findByUserId(userId, since)

            val totalEvents = userEvents.size
            val successfulEvents = userEvents.count { it.success }
            val failedEvents = totalEvents - successfulEvents

            val toolUsage = userEvents
                .filter { it.eventType == McpEventType.TOOL_CALL && it.toolName != null }
                .groupBy { it.toolName!! }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }

            val hourlyPattern = userEvents
                .groupBy { it.timestamp.hour }
                .mapValues { it.value.size }
                .toSortedMap()

            val averageSessionDuration = userEvents
                .filter { it.eventType in listOf(McpEventType.SESSION_CREATED, McpEventType.SESSION_CLOSED) }
                .groupBy { it.sessionId }
                .values
                .mapNotNull { sessionEvents ->
                    val created = sessionEvents.find { it.eventType == McpEventType.SESSION_CREATED }
                    val closed = sessionEvents.find { it.eventType == McpEventType.SESSION_CLOSED }
                    if (created != null && closed != null) {
                        java.time.Duration.between(created.timestamp, closed.timestamp).toMinutes()
                    } else null
                }
                .average()

            // Identify anomalies
            val anomalies = mutableListOf<String>()

            if (failedEvents > totalEvents * 0.2) {
                anomalies.add("High failure rate: ${(failedEvents.toDouble() / totalEvents * 100).toInt()}%")
            }

            val nightActivity = userEvents.count { it.timestamp.hour in 0..5 }
            if (nightActivity > totalEvents * 0.3) {
                anomalies.add("Unusual night activity: $nightActivity events between midnight and 6 AM")
            }

            return UserBehaviorAnalysis(
                userId = userId,
                analysisPeriod = Pair(since, LocalDateTime.now()),
                totalEvents = totalEvents.toLong(),
                successfulEvents = successfulEvents.toLong(),
                failedEvents = failedEvents.toLong(),
                successRate = if (totalEvents > 0) (successfulEvents.toDouble() / totalEvents * 100) else 0.0,
                topTools = toolUsage.take(5).toMap(),
                hourlyActivityPattern = hourlyPattern,
                averageSessionDurationMinutes = if (averageSessionDuration.isNaN()) null else averageSessionDuration.toLong(),
                anomalies = anomalies
            )

        } catch (e: Exception) {
            logger.error("Failed to analyze user behavior for userId={}", userId, e)
            throw RuntimeException("User behavior analysis failed: ${e.message}")
        }
    }

    /**
     * Get system performance overview.
     */
    fun getPerformanceOverview(
        hours: Int = 24
    ): PerformanceOverview {
        try {
            val since = LocalDateTime.now().minusHours(hours.toLong())

            val slowOperations = auditLogRepository.findSlowOperations(1000, since) // > 1 second
            val performanceStats = auditLogRepository.getPerformanceStatistics(since)
            val hourlyTrends = auditLogRepository.getHourlyPerformanceTrends(since)

            val operationMetrics = performanceStats.map {
                PerformanceMetric(
                    operation = it[0] as McpOperation,
                    operationCount = (it[1] as Number).toLong(),
                    avgDuration = (it[2] as Number).toLong(),
                    minDuration = (it[3] as Number).toLong(),
                    maxDuration = (it[4] as Number).toLong()
                )
            }

            val hourlyData = hourlyTrends.map {
                HourlyPerformanceData(
                    hour = (it[0] as Number).toInt(),
                    avgDuration = (it[1] as Number).toLong(),
                    operationCount = (it[2] as Number).toLong()
                )
            }

            return PerformanceOverview(
                analysisHours = hours,
                totalSlowOperations = slowOperations.size.toLong(),
                operationMetrics = operationMetrics,
                hourlyTrends = hourlyData,
                worstPerformingOperations = operationMetrics.sortedByDescending { it.avgDuration }.take(5)
            )

        } catch (e: Exception) {
            logger.error("Failed to get performance overview", e)
            throw RuntimeException("Performance overview generation failed: ${e.message}")
        }
    }

    /**
     * Clean up old audit logs based on retention policy.
     */
    fun cleanupOldAuditLogs(retentionDays: Int = 90): CleanupResult {
        val startTime = System.currentTimeMillis()

        try {
            val cutoffDate = LocalDateTime.now().minusDays(retentionDays.toLong())
            val countBefore = auditLogRepository.countOlderThan(cutoffDate)

            if (countBefore == 0L) {
                return CleanupResult(
                    success = true,
                    cleanedUpCount = 0,
                    durationMs = System.currentTimeMillis() - startTime,
                    message = "No old audit logs to clean up"
                )
            }

            val deletedCount = auditLogRepository.deleteOlderThan(cutoffDate)
            val duration = System.currentTimeMillis() - startTime

            logger.info("Cleaned up {} audit log entries older than {} days in {}ms",
                       deletedCount, retentionDays, duration)

            return CleanupResult(
                success = true,
                cleanedUpCount = deletedCount,
                durationMs = duration,
                message = "Cleaned up $deletedCount audit log entries"
            )

        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error("Audit log cleanup failed after {}ms", duration, e)

            return CleanupResult(
                success = false,
                cleanedUpCount = 0,
                durationMs = duration,
                message = "Cleanup failed: ${e.message}"
            )
        }
    }
}

// Data classes for audit service responses

data class SecurityEventSummary(
    val totalEvents: Int,
    val highRiskEvents: Int,
    val eventsByType: Map<McpEventType, Int>,
    val topSourceIps: Map<String, Int>,
    val timeRange: Pair<LocalDateTime, LocalDateTime>,
    val events: List<McpAuditLog>
)

data class ComplianceReport(
    val reportPeriod: Pair<LocalDateTime, LocalDateTime>,
    val generatedAt: LocalDateTime,
    val totalEvents: Long,
    val securityEventCount: Long,
    val userActivitySummaries: List<UserActivitySummary>,
    val apiKeyUsageSummaries: List<ApiKeyUsageSummary>,
    val toolUsageSummaries: List<ToolUsageSummary>,
    val performanceMetrics: List<PerformanceMetric>
)

data class UserActivitySummary(
    val userId: Long,
    val totalEvents: Long,
    val successRate: Double,
    val lastActivity: LocalDateTime
)

data class ApiKeyUsageSummary(
    val apiKeyId: Long,
    val eventCount: Long,
    val successRate: Double,
    val lastUsed: LocalDateTime
)

data class ToolUsageSummary(
    val toolName: String,
    val callCount: Long,
    val successRate: Double,
    val avgDuration: Long?
)

data class PerformanceMetric(
    val operation: McpOperation,
    val operationCount: Long,
    val avgDuration: Long,
    val minDuration: Long,
    val maxDuration: Long
)

data class UserBehaviorAnalysis(
    val userId: Long,
    val analysisPeriod: Pair<LocalDateTime, LocalDateTime>,
    val totalEvents: Long,
    val successfulEvents: Long,
    val failedEvents: Long,
    val successRate: Double,
    val topTools: Map<String, Int>,
    val hourlyActivityPattern: Map<Int, Int>,
    val averageSessionDurationMinutes: Long?,
    val anomalies: List<String>
)

data class PerformanceOverview(
    val analysisHours: Int,
    val totalSlowOperations: Long,
    val operationMetrics: List<PerformanceMetric>,
    val hourlyTrends: List<HourlyPerformanceData>,
    val worstPerformingOperations: List<PerformanceMetric>
)

data class HourlyPerformanceData(
    val hour: Int,
    val avgDuration: Long,
    val operationCount: Long
)