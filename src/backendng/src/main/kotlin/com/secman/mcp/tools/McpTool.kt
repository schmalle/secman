package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext

/**
 * Base interface for all MCP tools.
 * Each tool provides specific functionality accessible through the MCP protocol.
 *
 * Feature: 052-mcp-access-control
 * Tools now receive an execution context that includes:
 * - API key information
 * - Delegated user information (if User Delegation is enabled)
 * - Pre-computed accessible asset IDs for row-level access control
 */
interface McpTool {
    /**
     * Unique identifier for this tool.
     */
    val name: String

    /**
     * Human-readable description of what this tool does.
     */
    val description: String

    /**
     * The type of operation this tool performs (READ, WRITE, DELETE).
     */
    val operation: McpOperation

    /**
     * JSON schema defining the expected input parameters.
     */
    val inputSchema: Map<String, Any>

    /**
     * Execute the tool with the provided arguments and execution context.
     *
     * The context provides:
     * - API key identification
     * - Delegated user info (when User Delegation is enabled)
     * - Pre-computed accessible asset IDs for access control filtering
     *
     * Tools MUST use context.canAccessAsset() or context.getFilterableAssetIds()
     * to apply row-level access control when User Delegation is active.
     *
     * @param arguments Tool-specific arguments from the MCP call
     * @param context Execution context with access control information
     * @return Result of tool execution
     */
    suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult
}

/**
 * Result of executing an MCP tool.
 */
sealed class McpToolResult {
    abstract val isError: Boolean

    data class Success(
        val content: Any,
        val metadata: Map<String, Any> = emptyMap()
    ) : McpToolResult() {
        override val isError = false
    }

    data class Error(
        val code: String,
        val message: String,
        val details: Map<String, Any> = emptyMap()
    ) : McpToolResult() {
        override val isError = true
    }

    companion object {
        fun success(content: Any, metadata: Map<String, Any> = emptyMap()) = Success(content, metadata)
        fun error(code: String, message: String, details: Map<String, Any> = emptyMap()) = Error(code, message, details)
    }
}