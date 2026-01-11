package com.secman.dto.mcp

import io.micronaut.serde.annotation.Serdeable

/**
 * DTOs for MCP Streamable HTTP Transport (direct connection without Node.js).
 * Implements JSON-RPC 2.0 protocol as specified in MCP specification.
 *
 * @see https://modelcontextprotocol.io/specification/2024-11-05/basic/transports
 */

// ===== JSON-RPC 2.0 BASE TYPES =====

/**
 * Standard JSON-RPC 2.0 error codes.
 */
object JsonRpcErrorCodes {
    // Standard JSON-RPC errors
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // MCP-specific errors (reserved range -32000 to -32099)
    const val AUTH_REQUIRED = -32001
    const val AUTH_FAILED = -32002
    const val PERMISSION_DENIED = -32003
    const val SESSION_INVALID = -32004
    const val TOOL_NOT_FOUND = -32005
    const val RATE_LIMITED = -32006
}

/**
 * JSON-RPC 2.0 error object.
 */
@Serdeable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)

/**
 * JSON-RPC 2.0 request.
 * The id can be String, Number, or null (for notifications).
 */
@Serdeable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Any? = null,
    val method: String,
    val params: Map<String, Any>? = null
)

/**
 * JSON-RPC 2.0 response.
 * Either result OR error must be present (but not both).
 */
@Serdeable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Any? = null,
    val result: Any? = null,
    val error: JsonRpcError? = null
) {
    companion object {
        fun success(id: Any?, result: Any?): JsonRpcResponse {
            return JsonRpcResponse(id = id, result = result)
        }

        fun error(id: Any?, code: Int, message: String, data: Any? = null): JsonRpcResponse {
            return JsonRpcResponse(id = id, error = JsonRpcError(code, message, data))
        }

        fun parseError(id: Any? = null): JsonRpcResponse {
            return error(id, JsonRpcErrorCodes.PARSE_ERROR, "Parse error: Invalid JSON")
        }

        fun invalidRequest(id: Any?, message: String = "Invalid Request"): JsonRpcResponse {
            return error(id, JsonRpcErrorCodes.INVALID_REQUEST, message)
        }

        fun methodNotFound(id: Any?, method: String): JsonRpcResponse {
            return error(id, JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: $method")
        }

        fun invalidParams(id: Any?, message: String): JsonRpcResponse {
            return error(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid params: $message")
        }

        fun internalError(id: Any?, message: String = "Internal error"): JsonRpcResponse {
            return error(id, JsonRpcErrorCodes.INTERNAL_ERROR, message)
        }

        fun authRequired(id: Any?): JsonRpcResponse {
            return error(id, JsonRpcErrorCodes.AUTH_REQUIRED, "Authentication required: X-MCP-API-Key header missing")
        }

        fun authFailed(id: Any?, message: String = "Authentication failed"): JsonRpcResponse {
            return error(id, JsonRpcErrorCodes.AUTH_FAILED, message)
        }

        fun permissionDenied(id: Any?, message: String = "Permission denied"): JsonRpcResponse {
            return error(id, JsonRpcErrorCodes.PERMISSION_DENIED, message)
        }
    }
}

// ===== MCP INITIALIZE =====

/**
 * Client information sent during initialization.
 */
@Serdeable
data class McpClientImplementation(
    val name: String,
    val version: String
)

/**
 * Client capabilities sent during initialization.
 */
@Serdeable
data class McpClientCapabilities(
    val roots: McpRootsCapability? = null,
    val sampling: Map<String, Any>? = null,
    val experimental: Map<String, Any>? = null
)

/**
 * Roots capability for file system access.
 */
@Serdeable
data class McpRootsCapability(
    val listChanged: Boolean? = false
)

/**
 * Parameters for the initialize method.
 */
@Serdeable
data class McpInitializeParams(
    val protocolVersion: String,
    val capabilities: McpClientCapabilities,
    val clientInfo: McpClientImplementation
)

/**
 * Server information returned during initialization.
 */
@Serdeable
data class McpServerImplementation(
    val name: String,
    val version: String
)

/**
 * Server capabilities returned during initialization.
 */
@Serdeable
data class McpServerCapabilities(
    val tools: McpToolsCapability? = null,
    val resources: McpResourcesCapability? = null,
    val prompts: McpPromptsCapability? = null,
    val logging: Map<String, Any>? = null,
    val experimental: Map<String, Any>? = null
)

/**
 * Tools capability.
 */
@Serdeable
data class McpToolsCapability(
    val listChanged: Boolean? = false
)

/**
 * Resources capability.
 */
@Serdeable
data class McpResourcesCapability(
    val subscribe: Boolean? = false,
    val listChanged: Boolean? = false
)

/**
 * Prompts capability.
 */
@Serdeable
data class McpPromptsCapability(
    val listChanged: Boolean? = false
)

/**
 * Result of the initialize method.
 */
@Serdeable
data class McpInitializeResult(
    val protocolVersion: String,
    val capabilities: McpServerCapabilities,
    val serverInfo: McpServerImplementation,
    val instructions: String? = null
)

// ===== MCP TOOLS =====

/**
 * Tool definition returned by tools/list.
 */
@Serdeable
data class McpToolDefinitionDto(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>
)

/**
 * Result of tools/list method.
 */
@Serdeable
data class McpToolsListResult(
    val tools: List<McpToolDefinitionDto>
)

/**
 * Parameters for tools/call method.
 */
@Serdeable
data class McpToolsCallParams(
    val name: String,
    val arguments: Map<String, Any>? = emptyMap()
)

/**
 * Content item in tool call result.
 */
@Serdeable
data class McpContentItem(
    val type: String,  // "text", "image", "resource"
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null
)

/**
 * Result of tools/call method.
 */
@Serdeable
data class McpToolsCallResult(
    val content: List<McpContentItem>,
    val isError: Boolean? = false
)

// ===== MCP PING =====

/**
 * Empty result for ping method.
 */
@Serdeable
class McpPingResult

// ===== MCP PROTOCOL CONSTANTS =====

/**
 * Supported MCP protocol versions.
 */
object McpProtocolVersions {
    const val V2024_11_05 = "2024-11-05"
    const val LATEST = V2024_11_05

    val SUPPORTED = setOf(V2024_11_05)

    fun isSupported(version: String): Boolean = version in SUPPORTED
}

/**
 * MCP method names.
 */
object McpMethods {
    const val INITIALIZE = "initialize"
    const val INITIALIZED = "notifications/initialized"
    const val PING = "ping"
    const val TOOLS_LIST = "tools/list"
    const val TOOLS_CALL = "tools/call"
    const val CANCELLED = "notifications/cancelled"
}
