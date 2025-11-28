package com.secman.domain

import jakarta.persistence.*
import jakarta.validation.constraints.*
import java.time.LocalDateTime
import io.micronaut.serde.annotation.Serdeable
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * MCP Audit Log entity for tracking all MCP-related security events.
 *
 * This entity provides comprehensive audit trail for MCP operations including
 * authentication events, tool calls, session management, and security incidents.
 * Critical for compliance, security monitoring, and forensic analysis.
 */
@Entity
@Table(
    name = "mcp_audit_logs",
    indexes = [
        Index(name = "idx_mcp_audit_timestamp", columnList = "timestamp"),
        Index(name = "idx_mcp_audit_event_type", columnList = "eventType"),
        Index(name = "idx_mcp_audit_api_key", columnList = "apiKeyId"),
        Index(name = "idx_mcp_audit_session", columnList = "sessionId"),
        Index(name = "idx_mcp_audit_user", columnList = "userId"),
        Index(name = "idx_mcp_audit_success", columnList = "success"),
        Index(name = "idx_mcp_audit_severity", columnList = "severity"),
        Index(name = "idx_mcp_audit_composite", columnList = "eventType, timestamp, success"),
        Index(name = "idx_mcp_audit_delegated_user", columnList = "delegatedUserEmail, timestamp")
    ]
)
@Serdeable
data class McpAuditLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    /**
     * Type of MCP event being audited.
     */
    @Column(name = "event_type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @NotNull
    val eventType: McpEventType,

    /**
     * Timestamp when the event occurred.
     */
    @Column(name = "timestamp", nullable = false)
    @NotNull
    val timestamp: LocalDateTime = LocalDateTime.now(),

    /**
     * ID of the API key involved in this event (if applicable).
     */
    @Column(name = "api_key_id")
    val apiKeyId: Long? = null,

    /**
     * ID of the MCP session involved in this event (if applicable).
     */
    @Column(name = "session_id", length = 64)
    @Size(max = 64)
    val sessionId: String? = null,

    /**
     * ID of the user who owns the API key (if applicable).
     */
    @Column(name = "user_id")
    val userId: Long? = null,

    /**
     * Type of MCP operation performed (if applicable).
     */
    @Column(name = "operation", length = 30)
    @Enumerated(EnumType.STRING)
    val operation: McpOperation? = null,

    /**
     * Name of the tool called (for TOOL_CALL events).
     */
    @Column(name = "tool_name", length = 100)
    @Size(max = 100)
    val toolName: String? = null,

    /**
     * Whether the operation was successful.
     */
    @Column(name = "success", nullable = false)
    @NotNull
    val success: Boolean,

    /**
     * Error code if the operation failed.
     */
    @Column(name = "error_code", length = 50)
    @Size(max = 50)
    val errorCode: String? = null,

    /**
     * Human-readable error message if the operation failed.
     */
    @Column(name = "error_message", length = 500)
    @Size(max = 500)
    val errorMessage: String? = null,

    /**
     * Client IP address for this event.
     */
    @Column(name = "client_ip", length = 45) // IPv6 max length
    @Size(max = 45)
    val clientIp: String? = null,

    /**
     * Client User-Agent string for this event.
     */
    @Column(name = "user_agent", length = 500)
    @Size(max = 500)
    val userAgent: String? = null,

    /**
     * Additional context data specific to this event type.
     * Stored as JSON string for flexible event-specific information.
     */
    @Column(name = "context_data", length = 2000)
    @Size(max = 2000)
    val contextData: String? = null,

    /**
     * Request ID for correlation across multiple log entries.
     */
    @Column(name = "request_id", length = 100)
    @Size(max = 100)
    val requestId: String? = null,

    /**
     * Duration of the operation in milliseconds (if applicable).
     */
    @Column(name = "duration_ms")
    val durationMs: Long? = null,

    /**
     * Size of request payload in bytes (if applicable).
     */
    @Column(name = "request_size_bytes")
    val requestSizeBytes: Long? = null,

    /**
     * Size of response payload in bytes (if applicable).
     */
    @Column(name = "response_size_bytes")
    val responseSizeBytes: Long? = null,

    /**
     * Severity level of this audit event.
     * INFO for normal operations, WARN for suspicious activity, ERROR for failures.
     */
    @Column(name = "severity", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    @NotNull
    val severity: AuditSeverity = AuditSeverity.INFO,

    /**
     * Email of the user on whose behalf the request was made (if delegation was used).
     * Feature: 050-mcp-user-delegation
     */
    @Column(name = "delegated_user_email", length = 255)
    @Size(max = 255)
    val delegatedUserEmail: String? = null,

    /**
     * ID of the delegated user (for joins, if delegation was used).
     * Feature: 050-mcp-user-delegation
     */
    @Column(name = "delegated_user_id")
    val delegatedUserId: Long? = null
) {
    /**
     * Parse the context data JSON into a JsonNode for programmatic access.
     */
    fun getContextDataAsJson(): JsonNode? {
        return if (contextData.isNullOrBlank()) {
            null
        } else {
            try {
                ObjectMapper().readTree(contextData)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Check if this is a security-related event that requires attention.
     */
    fun isSecurityEvent(): Boolean {
        return when (eventType) {
            McpEventType.AUTH_FAILURE,
            McpEventType.PERMISSION_DENIED,
            McpEventType.RATE_LIMITED,
            McpEventType.SESSION_EXPIRED,
            McpEventType.INVALID_REQUEST -> true
            else -> severity == AuditSeverity.ERROR
        }
    }

    /**
     * Check if this is a performance-related event.
     */
    fun isPerformanceEvent(): Boolean {
        return durationMs != null && durationMs > 1000 // Operations taking > 1 second
    }

    /**
     * Get a human-readable summary of this audit event.
     */
    fun getSummary(): String {
        val operation = if (toolName != null) "tool '$toolName'" else operation?.name?.lowercase() ?: "operation"
        val result = if (success) "succeeded" else "failed"
        val user = if (userId != null) " for user $userId" else ""
        val delegated = if (delegatedUserEmail != null) " (delegated: $delegatedUserEmail)" else ""
        val session = if (sessionId != null) " in session ${sessionId.take(8)}..." else ""

        return "${eventType.name.lowercase().replace('_', ' ')} - $operation $result$user$delegated$session"
    }

    /**
     * Check if this request was made via delegation.
     * Feature: 050-mcp-user-delegation
     */
    fun isDelegatedRequest(): Boolean {
        return delegatedUserEmail != null
    }

    /**
     * Get performance metrics summary if available.
     */
    fun getPerformanceMetrics(): Map<String, Any?> {
        return mapOf(
            "durationMs" to durationMs,
            "requestSizeBytes" to requestSizeBytes,
            "responseSizeBytes" to responseSizeBytes,
            "throughputBytesPerSec" to if (durationMs != null && durationMs > 0 && responseSizeBytes != null) {
                (responseSizeBytes * 1000) / durationMs
            } else null
        )
    }

    companion object {
        /**
         * Create an audit log for successful authentication.
         */
        fun createAuthSuccess(
            apiKeyId: Long,
            userId: Long,
            sessionId: String? = null,
            clientIp: String? = null,
            userAgent: String? = null,
            requestId: String? = null
        ): McpAuditLog {
            return McpAuditLog(
                eventType = McpEventType.AUTH_SUCCESS,
                apiKeyId = apiKeyId,
                userId = userId,
                sessionId = sessionId,
                success = true,
                clientIp = clientIp,
                userAgent = userAgent,
                requestId = requestId,
                severity = AuditSeverity.INFO
            )
        }

        /**
         * Create an audit log for failed authentication.
         */
        fun createAuthFailure(
            apiKeyId: Long? = null,
            errorCode: String,
            errorMessage: String,
            clientIp: String? = null,
            userAgent: String? = null,
            requestId: String? = null
        ): McpAuditLog {
            return McpAuditLog(
                eventType = McpEventType.AUTH_FAILURE,
                apiKeyId = apiKeyId,
                success = false,
                errorCode = errorCode,
                errorMessage = errorMessage,
                clientIp = clientIp,
                userAgent = userAgent,
                requestId = requestId,
                severity = AuditSeverity.WARN
            )
        }

        /**
         * Create an audit log for tool call execution.
         */
        fun createToolCall(
            apiKeyId: Long,
            userId: Long,
            sessionId: String,
            toolName: String,
            operation: McpOperation,
            success: Boolean,
            durationMs: Long? = null,
            errorCode: String? = null,
            errorMessage: String? = null,
            contextData: String? = null,
            requestId: String? = null,
            requestSize: Long? = null,
            responseSize: Long? = null
        ): McpAuditLog {
            return McpAuditLog(
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
                severity = if (success) AuditSeverity.INFO else AuditSeverity.ERROR
            )
        }

        /**
         * Create an audit log for session lifecycle events.
         */
        fun createSessionEvent(
            eventType: McpEventType,
            sessionId: String,
            apiKeyId: Long,
            userId: Long,
            success: Boolean,
            errorCode: String? = null,
            errorMessage: String? = null,
            contextData: String? = null
        ): McpAuditLog {
            return McpAuditLog(
                eventType = eventType,
                sessionId = sessionId,
                apiKeyId = apiKeyId,
                userId = userId,
                success = success,
                errorCode = errorCode,
                errorMessage = errorMessage,
                contextData = contextData,
                severity = if (success) AuditSeverity.INFO else AuditSeverity.ERROR
            )
        }

        /**
         * Create context data JSON for tool calls.
         */
        fun createToolCallContext(
            arguments: Map<String, Any>,
            resultSize: Int? = null,
            cacheHit: Boolean? = null
        ): String {
            val context = mutableMapOf<String, Any>(
                "arguments" to arguments
            )
            if (resultSize != null) context["resultSize"] = resultSize
            if (cacheHit != null) context["cacheHit"] = cacheHit

            return ObjectMapper().writeValueAsString(context)
        }

        /**
         * Query helper for finding security events within a time range.
         */
        fun securityEventsQuery(
            startTime: LocalDateTime,
            endTime: LocalDateTime
        ): String {
            return """
                eventType IN ('AUTH_FAILURE', 'PERMISSION_DENIED', 'RATE_LIMITED',
                             'SESSION_EXPIRED', 'INVALID_REQUEST')
                AND timestamp BETWEEN :startTime AND :endTime
                ORDER BY timestamp DESC
            """.trimIndent()
        }

        /**
         * Query helper for finding performance issues.
         */
        fun performanceIssuesQuery(minDurationMs: Long = 1000): String {
            return """
                durationMs >= :minDurationMs
                ORDER BY durationMs DESC, timestamp DESC
            """.trimIndent()
        }

        /**
         * Query helper for user activity analysis.
         */
        fun userActivityQuery(userId: Long, startTime: LocalDateTime): String {
            return """
                userId = :userId
                AND timestamp >= :startTime
                ORDER BY timestamp DESC
            """.trimIndent()
        }
    }

    override fun toString(): String {
        return "McpAuditLog(id=$id, eventType=$eventType, timestamp=$timestamp, " +
               "success=$success, userId=$userId, sessionId=${sessionId?.take(8)}, " +
               "operation=$operation, toolName=$toolName, severity=$severity)"
    }
}