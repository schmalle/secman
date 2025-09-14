package com.secman.domain

import jakarta.persistence.*
import jakarta.validation.constraints.*
import java.time.LocalDateTime
import io.micronaut.serde.annotation.Serdeable
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * MCP Session entity representing active MCP client connections and their state.
 *
 * Each session is tied to an API key and tracks the client's capabilities,
 * connection type, and activity for session management and cleanup.
 */
@Entity
@Table(
    name = "mcp_sessions",
    indexes = [
        Index(name = "idx_mcp_session_id", columnList = "sessionId", unique = true),
        Index(name = "idx_mcp_sessions_api_key_active", columnList = "apiKeyId, isActive"),
        Index(name = "idx_mcp_sessions_last_activity", columnList = "lastActivity"),
        Index(name = "idx_mcp_sessions_created_at", columnList = "createdAt")
    ]
)
@Serdeable
data class McpSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    /**
     * Secure session identifier exposed to clients.
     * Must be cryptographically secure and unique across all sessions.
     */
    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    @NotBlank
    @Size(min = 16, max = 64)
    val sessionId: String,

    /**
     * Foreign key reference to the McpApiKey used for this session.
     */
    @Column(name = "api_key_id", nullable = false)
    @NotNull
    val apiKeyId: Long,

    /**
     * Information about the MCP client (name, version, etc.).
     * Stored as JSON string for flexibility.
     */
    @Column(name = "client_info", nullable = false, length = 1000)
    @NotBlank
    @Size(max = 1000)
    val clientInfo: String,

    /**
     * Negotiated MCP capabilities between client and server.
     * Stored as JSON string containing the capability agreement.
     */
    @Column(name = "capabilities", nullable = false, length = 2000)
    @NotBlank
    @Size(max = 2000)
    val capabilities: String,

    /**
     * Type of connection used for this session (SSE, WebSocket, HTTP).
     */
    @Column(name = "connection_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @NotNull
    val connectionType: McpConnectionType = McpConnectionType.HTTP,

    /**
     * Timestamp when the session was created.
     */
    @Column(name = "created_at", nullable = false)
    @NotNull
    val createdAt: LocalDateTime = LocalDateTime.now(),

    /**
     * Timestamp of the last activity in this session.
     * Updated on each request/response.
     */
    @Column(name = "last_activity", nullable = false)
    @NotNull
    var lastActivity: LocalDateTime = LocalDateTime.now(),

    /**
     * Whether the session is currently active.
     * Inactive sessions cannot be used and should be cleaned up.
     */
    @Column(name = "is_active", nullable = false)
    @NotNull
    var isActive: Boolean = true,

    /**
     * Optional notes about the session (e.g., termination reason).
     */
    @Column(name = "notes", length = 500)
    @Size(max = 500)
    var notes: String? = null,

    /**
     * Client's IP address for this session (for audit purposes).
     */
    @Column(name = "client_ip", length = 45) // IPv6 max length
    @Size(max = 45)
    val clientIp: String? = null,

    /**
     * Client's User-Agent string (for audit purposes).
     */
    @Column(name = "user_agent", length = 500)
    @Size(max = 500)
    val userAgent: String? = null
) {
    /**
     * Parse the client info JSON into a JsonNode for programmatic access.
     */
    fun getClientInfoAsJson(): JsonNode? {
        return try {
            ObjectMapper().readTree(clientInfo)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse the capabilities JSON into a JsonNode for programmatic access.
     */
    fun getCapabilitiesAsJson(): JsonNode? {
        return try {
            ObjectMapper().readTree(capabilities)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the client name from client info, with fallback.
     */
    fun getClientName(): String {
        return getClientInfoAsJson()?.get("name")?.asText() ?: "Unknown Client"
    }

    /**
     * Get the client version from client info, with fallback.
     */
    fun getClientVersion(): String {
        return getClientInfoAsJson()?.get("version")?.asText() ?: "Unknown Version"
    }

    /**
     * Update the last activity timestamp to now.
     */
    fun updateActivity() {
        lastActivity = LocalDateTime.now()
    }

    /**
     * Check if the session is expired based on timeout settings.
     */
    fun isExpired(timeoutMinutes: Int = 60): Boolean {
        val timeout = LocalDateTime.now().minusMinutes(timeoutMinutes.toLong())
        return lastActivity.isBefore(timeout)
    }

    /**
     * Check if the session is valid (active and not expired).
     */
    fun isValid(timeoutMinutes: Int = 60): Boolean {
        return isActive && !isExpired(timeoutMinutes)
    }

    /**
     * Mark the session as inactive with optional reason.
     */
    fun deactivate(reason: String? = null) {
        isActive = false
        if (reason != null) {
            notes = if (notes.isNullOrBlank()) reason else "$notes; $reason"
        }
    }

    /**
     * Get session duration in minutes.
     */
    fun getDurationMinutes(): Long {
        val end = if (isActive) LocalDateTime.now() else lastActivity
        return java.time.Duration.between(createdAt, end).toMinutes()
    }

    /**
     * Get time since last activity in minutes.
     */
    fun getIdleTimeMinutes(): Long {
        return java.time.Duration.between(lastActivity, LocalDateTime.now()).toMinutes()
    }

    /**
     * Check if this session supports streaming (SSE or WebSocket).
     */
    fun supportsStreaming(): Boolean {
        return connectionType.supportsStreaming()
    }

    /**
     * Check if this session supports bidirectional communication.
     */
    fun supportsBidirectional(): Boolean {
        return connectionType.supportsBidirectional()
    }

    /**
     * Get expected timeout based on connection type.
     */
    fun getConnectionTimeoutMinutes(): Int {
        return connectionType.getDefaultTimeoutMinutes()
    }

    companion object {
        /**
         * Generate a cryptographically secure session ID.
         */
        fun generateSessionId(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            return (1..32)
                .map { chars.random() }
                .joinToString("")
        }

        /**
         * Create client info JSON string from name and version.
         */
        fun createClientInfoJson(name: String, version: String, additionalInfo: Map<String, Any> = emptyMap()): String {
            val info = mutableMapOf<String, Any>(
                "name" to name,
                "version" to version
            )
            info.putAll(additionalInfo)
            return ObjectMapper().writeValueAsString(info)
        }

        /**
         * Create capabilities JSON string from capability maps.
         */
        fun createCapabilitiesJson(
            tools: Map<String, Any> = emptyMap(),
            resources: Map<String, Any> = emptyMap(),
            prompts: Map<String, Any> = emptyMap()
        ): String {
            val capabilities = mapOf(
                "tools" to tools,
                "resources" to resources,
                "prompts" to prompts
            )
            return ObjectMapper().writeValueAsString(capabilities)
        }

        /**
         * Validate client info JSON format.
         */
        fun validateClientInfoJson(clientInfo: String): Boolean {
            return try {
                val json = ObjectMapper().readTree(clientInfo)
                json.has("name") && json.has("version")
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Validate capabilities JSON format.
         */
        fun validateCapabilitiesJson(capabilities: String): Boolean {
            return try {
                val json = ObjectMapper().readTree(capabilities)
                json.has("tools") && json.has("resources") && json.has("prompts")
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Clean up expired sessions query helper.
         */
        fun isExpiredQuery(timeoutMinutes: Int): String {
            return "lastActivity < :cutoffTime"
        }
    }

    override fun toString(): String {
        return "McpSession(id=$id, sessionId='$sessionId', apiKeyId=$apiKeyId, " +
               "connectionType=$connectionType, clientName='${getClientName()}', " +
               "isActive=$isActive, durationMinutes=${getDurationMinutes()}, " +
               "idleTimeMinutes=${getIdleTimeMinutes()})"
    }
}