package com.secman.domain

/**
 * Enumeration of MCP (Model Context Protocol) audit event types.
 * Used for comprehensive logging and monitoring of MCP server activities.
 */
enum class McpEventType {
    /**
     * Successful API key authentication
     */
    AUTH_SUCCESS,

    /**
     * Failed API key authentication attempt
     */
    AUTH_FAILURE,

    /**
     * New MCP session established
     */
    SESSION_CREATED,

    /**
     * MCP session expired due to timeout
     */
    SESSION_EXPIRED,

    /**
     * MCP session explicitly closed by client
     */
    SESSION_CLOSED,

    /**
     * MCP tool invocation (successful or failed)
     */
    TOOL_CALL,

    /**
     * Access denied due to insufficient permissions
     */
    PERMISSION_DENIED,

    /**
     * System error during MCP operation
     */
    ERROR,

    /**
     * Request rate limited or throttled
     */
    RATE_LIMITED,

    /**
     * Invalid or malformed request
     */
    INVALID_REQUEST,

    /**
     * MCP resource access (requirements, assessments, etc.)
     */
    RESOURCE_ACCESS,

    /**
     * Server-Sent Events connection established
     */
    SSE_CONNECT,

    /**
     * Server-Sent Events connection closed
     */
    SSE_DISCONNECT,

    /**
     * API key generated or modified
     */
    API_KEY_MANAGEMENT,

    /**
     * MCP server capabilities requested
     */
    CAPABILITIES_REQUEST;

    /**
     * Get display name for UI and logging
     */
    fun getDisplayName(): String {
        return when (this) {
            AUTH_SUCCESS -> "Authentication Success"
            AUTH_FAILURE -> "Authentication Failure"
            SESSION_CREATED -> "Session Created"
            SESSION_EXPIRED -> "Session Expired"
            SESSION_CLOSED -> "Session Closed"
            TOOL_CALL -> "Tool Called"
            PERMISSION_DENIED -> "Permission Denied"
            ERROR -> "System Error"
            RATE_LIMITED -> "Rate Limited"
            INVALID_REQUEST -> "Invalid Request"
            RESOURCE_ACCESS -> "Resource Accessed"
            SSE_CONNECT -> "SSE Connected"
            SSE_DISCONNECT -> "SSE Disconnected"
            API_KEY_MANAGEMENT -> "API Key Management"
            CAPABILITIES_REQUEST -> "Capabilities Request"
        }
    }

    /**
     * Check if this event type represents a security concern
     */
    fun isSecurityEvent(): Boolean {
        return when (this) {
            AUTH_FAILURE, PERMISSION_DENIED, RATE_LIMITED, INVALID_REQUEST -> true
            else -> false
        }
    }

    /**
     * Check if this event type represents an error condition
     */
    fun isErrorEvent(): Boolean {
        return when (this) {
            AUTH_FAILURE, ERROR, PERMISSION_DENIED, RATE_LIMITED, INVALID_REQUEST -> true
            else -> false
        }
    }

    /**
     * Get log level for this event type
     */
    fun getLogLevel(): String {
        return when (this) {
            AUTH_FAILURE, PERMISSION_DENIED, ERROR, INVALID_REQUEST -> "WARN"
            RATE_LIMITED -> "INFO"
            AUTH_SUCCESS, SESSION_CREATED, SESSION_CLOSED, TOOL_CALL,
            RESOURCE_ACCESS, SSE_CONNECT, SSE_DISCONNECT,
            API_KEY_MANAGEMENT, CAPABILITIES_REQUEST -> "INFO"
            SESSION_EXPIRED -> "DEBUG"
        }
    }
}