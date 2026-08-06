package com.secman.domain

/**
 * Enumeration of MCP (Model Context Protocol) connection types.
 * Defines the transport mechanism used for MCP client-server communication.
 */
enum class McpConnectionType {
    /**
     * Server-Sent Events - unidirectional streaming from server to client
     * Best for real-time notifications and updates
     */
    SSE,

    /**
     * WebSocket - bidirectional streaming connection
     * Best for interactive sessions with frequent back-and-forth communication
     */
    WEBSOCKET,

    /**
     * HTTP - traditional request-response pattern
     * Best for simple one-off requests and compatibility
     */
    HTTP,

    /**
     * Streamable HTTP - MCP native HTTP transport with JSON-RPC 2.0
     * Best for direct connections from Claude Desktop without Node.js middleware
     * Supports both single requests and batched requests
     */
    STREAMABLE_HTTP;

    /**
     * Get display name for UI
     */
    fun getDisplayName(): String {
        return when (this) {
            SSE -> "Server-Sent Events"
            WEBSOCKET -> "WebSocket"
            HTTP -> "HTTP"
            STREAMABLE_HTTP -> "Streamable HTTP (MCP Native)"
        }
    }

    /**
     * Get description for UI and documentation
     */
    fun getDescription(): String {
        return when (this) {
            SSE -> "Real-time streaming from server to client, ideal for notifications"
            WEBSOCKET -> "Bidirectional streaming, ideal for interactive sessions"
            HTTP -> "Traditional request-response, maximum compatibility"
            STREAMABLE_HTTP -> "MCP native HTTP transport, direct connection without middleware"
        }
    }

    /**
     * Check if connection type supports streaming
     */
    fun supportsStreaming(): Boolean {
        return when (this) {
            SSE, WEBSOCKET -> true
            HTTP, STREAMABLE_HTTP -> false
        }
    }

    /**
     * Check if connection type supports bidirectional communication
     */
    fun supportsBidirectional(): Boolean {
        return when (this) {
            WEBSOCKET -> true
            SSE, HTTP, STREAMABLE_HTTP -> false
        }
    }

    /**
     * Get expected connection timeout in minutes
     */
    fun getDefaultTimeoutMinutes(): Int {
        return when (this) {
            HTTP -> 2 // Short timeout for HTTP requests
            STREAMABLE_HTTP -> 5 // Slightly longer for MCP protocol handshake
            SSE -> 60 // Longer timeout for streaming connections
            WEBSOCKET -> 60 // Longer timeout for interactive sessions
        }
    }

    /**
     * Get maximum concurrent connections recommended for this type
     */
    fun getMaxConcurrentConnections(): Int {
        return when (this) {
            HTTP, STREAMABLE_HTTP -> 1000 // HTTP can handle more concurrent requests
            SSE -> 500 // Streaming connections use more resources
            WEBSOCKET -> 200 // Most resource intensive
        }
    }

    /**
     * Get content type header for this connection type
     */
    fun getContentType(): String {
        return when (this) {
            SSE -> "text/event-stream"
            WEBSOCKET -> "application/json" // WebSocket typically negotiates its own protocol
            HTTP, STREAMABLE_HTTP -> "application/json"
        }
    }

    /**
     * Check if connection type requires keep-alive
     */
    fun requiresKeepAlive(): Boolean {
        return when (this) {
            SSE, WEBSOCKET -> true
            HTTP, STREAMABLE_HTTP -> false
        }
    }
}