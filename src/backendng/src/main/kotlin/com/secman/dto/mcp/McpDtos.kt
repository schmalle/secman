package com.secman.dto.mcp

import io.micronaut.serde.annotation.Serdeable

// ===== CORE MCP PROTOCOL DTOS =====

/**
 * Standard MCP error response.
 */
@Serdeable
data class McpErrorResponse(
    val code: String,
    val message: String,
    val data: Map<String, Any>? = null
)

/**
 * MCP server capabilities response.
 */
@Serdeable
data class McpCapabilitiesResponse(
    val capabilities: Map<String, Any>? = null,
    val serverInfo: Map<String, Any>? = null,
    val error: McpErrorResponse? = null
)

// ===== SESSION MANAGEMENT DTOS =====

/**
 * MCP client information.
 */
@Serdeable
data class McpClientInfo(
    val name: String,
    val version: String,
    val additionalInfo: Map<String, Any>? = null
)

/**
 * Request to create a new MCP session.
 */
@Serdeable
data class McpSessionCreateRequest(
    val capabilities: Map<String, Any>,
    val clientInfo: McpClientInfo
)

/**
 * Response for session creation or session info.
 */
@Serdeable
data class McpSessionResponse(
    val sessionId: String? = null,
    val capabilities: Map<String, Any>? = null,
    val serverInfo: Map<String, Any>? = null,
    val error: McpErrorResponse? = null
)

// ===== TOOL EXECUTION DTOS =====

/**
 * Parameters for MCP tool call.
 */
@Serdeable
data class McpToolCallParams(
    val name: String,
    val arguments: Map<String, Any> = emptyMap()
)

/**
 * JSON-RPC request for tool execution.
 */
@Serdeable
data class McpToolCallRequest(
    val jsonrpc: String,
    val id: String,
    val method: String,
    val params: McpToolCallParams
)

/**
 * Result of tool execution.
 */
@Serdeable
data class McpToolResult(
    val content: Any,
    val isError: Boolean = false,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * JSON-RPC response for tool execution.
 */
@Serdeable
data class McpToolCallResponse(
    val jsonrpc: String,
    val id: String,
    val result: McpToolResult? = null,
    val error: McpErrorResponse? = null
)

// ===== TOOL DEFINITION DTOS =====

/**
 * MCP tool definition for capabilities response.
 */
@Serdeable
data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>
)

// ===== API KEY MANAGEMENT DTOS =====

/**
 * Request to create a new MCP API key.
 */
@Serdeable
data class McpApiKeyCreateRequest(
    val name: String = "", // Allow empty string, validation will catch it
    val permissions: List<String>,
    val expiresAt: String? = null, // ISO 8601 format
    val notes: String? = null
)

/**
 * Response for API key creation.
 */
@Serdeable
data class McpApiKeyCreateResponse(
    val keyId: String? = null,
    val apiKey: String? = null, // Only returned once during creation
    val name: String? = null,
    val permissions: List<String>? = null,
    val expiresAt: String? = null,
    val createdAt: String? = null,
    val error: McpErrorResponse? = null
)

/**
 * API key information (without secret).
 */
@Serdeable
data class McpApiKeyInfo(
    val id: Long,
    val keyId: String,
    val name: String,
    val permissions: List<String>,
    val isActive: Boolean,
    val lastUsedAt: String?,
    val expiresAt: String?,
    val createdAt: String,
    val notes: String?
)

/**
 * Response for listing API keys.
 */
@Serdeable
data class McpApiKeyListResponse(
    val apiKeys: List<McpApiKeyInfo>? = null,
    val total: Int? = null,
    val error: McpErrorResponse? = null
)

// ===== STATISTICS AND MONITORING DTOS =====

/**
 * MCP system statistics.
 */
@Serdeable
data class McpSystemStatistics(
    val sessions: McpSessionStatistics,
    val apiKeys: McpApiKeyStatistics,
    val tools: McpToolStatistics,
    val security: McpSecurityStatistics
)

@Serdeable
data class McpSessionStatistics(
    val totalActive: Long,
    val recentlyActive: Long,
    val byConnectionType: Map<String, Long>,
    val utilizationPercent: Double
)

@Serdeable
data class McpApiKeyStatistics(
    val totalActive: Long,
    val totalInactive: Long,
    val expiringSoon: Long,
    val recentlyUsed: Long
)

@Serdeable
data class McpToolStatistics(
    val totalCalls: Long,
    val successRate: Double,
    val topTools: List<Map<String, Any>>,
    val averageResponseTime: Double
)

@Serdeable
data class McpSecurityStatistics(
    val authFailures: Long,
    val permissionDenials: Long,
    val rateLimitHits: Long,
    val suspiciousActivity: Long
)

// ===== AUDIT AND LOGGING DTOS =====

/**
 * MCP audit log entry.
 */
@Serdeable
data class McpAuditLogEntry(
    val id: Long,
    val timestamp: String,
    val eventType: String,
    val apiKeyId: Long?,
    val sessionId: String?,
    val toolName: String?,
    val operation: String?,
    val success: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
    val durationMs: Long?,
    val clientIp: String?,
    val userAgent: String?
)

/**
 * Response for audit log queries.
 */
@Serdeable
data class McpAuditLogResponse(
    val logs: List<McpAuditLogEntry>? = null,
    val total: Long? = null,
    val page: Int? = null,
    val pageSize: Int? = null,
    val error: McpErrorResponse? = null
)

// ===== TOOL PERMISSION DTOS =====

/**
 * Request to grant tool permission.
 */
@Serdeable
data class McpToolPermissionGrantRequest(
    val apiKeyId: Long,
    val toolName: String,
    val parameterRestrictions: Map<String, Any>? = null,
    val maxCallsPerHour: Int? = null,
    val allowCaching: Boolean = true,
    val priority: Int = 0,
    val notes: String? = null
)

/**
 * Tool permission information.
 */
@Serdeable
data class McpToolPermissionInfo(
    val id: Long,
    val apiKeyId: Long,
    val toolName: String,
    val isActive: Boolean,
    val parameterRestrictions: String?,
    val maxCallsPerHour: Int?,
    val allowCaching: Boolean,
    val priority: Int,
    val createdAt: String,
    val updatedAt: String,
    val notes: String?
)

/**
 * Response for tool permission operations.
 */
@Serdeable
data class McpToolPermissionResponse(
    val permission: McpToolPermissionInfo? = null,
    val permissions: List<McpToolPermissionInfo>? = null,
    val success: Boolean? = null,
    val error: McpErrorResponse? = null
)

// ===== SERVER-SENT EVENTS DTOS =====

/**
 * SSE event for real-time MCP updates.
 */
@Serdeable
data class McpSseEvent(
    val event: String,
    val data: Map<String, Any>,
    val timestamp: String,
    val sessionId: String
)

// ===== HEALTH CHECK DTOS =====

/**
 * MCP server health check response.
 */
@Serdeable
data class McpHealthCheckResponse(
    val status: String, // HEALTHY, WARNING, CRITICAL
    val uptime: String,
    val version: String,
    val activeSessions: Long,
    val lastChecked: String,
    val details: Map<String, Any>? = null
)

// ===== CONFIGURATION DTOS =====

/**
 * MCP server configuration.
 */
@Serdeable
data class McpServerConfig(
    val maxConcurrentSessions: Int,
    val maxSessionsPerKey: Int,
    val sessionTimeoutMinutes: Int,
    val defaultRateLimit: Int,
    val enableAuditLogging: Boolean,
    val enableCaching: Boolean
)

/**
 * Request to update MCP server configuration.
 */
@Serdeable
data class McpConfigUpdateRequest(
    val maxConcurrentSessions: Int? = null,
    val maxSessionsPerKey: Int? = null,
    val sessionTimeoutMinutes: Int? = null,
    val defaultRateLimit: Int? = null,
    val enableAuditLogging: Boolean? = null,
    val enableCaching: Boolean? = null
)

// ===== BULK OPERATION DTOS =====

/**
 * Request for bulk API key operations.
 */
@Serdeable
data class McpBulkApiKeyRequest(
    val operation: String, // CREATE, DEACTIVATE, DELETE
    val keys: List<McpApiKeyCreateRequest>? = null,
    val keyIds: List<String>? = null,
    val reason: String? = null
)

/**
 * Response for bulk operations.
 */
@Serdeable
data class McpBulkOperationResponse(
    val totalRequested: Int,
    val successful: Int,
    val failed: Int,
    val errors: List<String> = emptyList(),
    val results: List<Any> = emptyList()
)