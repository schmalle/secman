package com.secman.mcp.tools

import com.secman.dto.mcp.McpExecutionContext

/**
 * Shared guard helpers for MCP tool `execute()` preambles.
 *
 * Each returns an [McpToolResult.Error] when the precondition fails, or null when
 * execution may proceed — use as:
 *
 *     requireDelegation(context)?.let { return it }
 *
 * The error codes and messages are the exact strings the tools have always
 * returned; MCP clients and E2E scripts match on them, so never reword here and
 * always pass the caller's own `code`/`message` where the tool had one.
 */

/** Gate for tools that require User Delegation (audit trail). */
fun requireDelegation(context: McpExecutionContext): McpToolResult.Error? =
    if (!context.hasDelegation()) {
        McpToolResult.Error(
            "DELEGATION_REQUIRED",
            "User Delegation must be enabled to use this tool"
        )
    } else null

/**
 * Gate for tools restricted to specific delegated-user roles. Admin API keys
 * always pass; otherwise at least one of [roles] must be present on the
 * delegated user.
 */
fun requireAnyRole(
    context: McpExecutionContext,
    vararg roles: String,
    code: String = "FORBIDDEN",
    message: String = "Insufficient role to use this tool"
): McpToolResult.Error? =
    if (context.isAdmin || roles.any { context.delegatedUserRoles?.contains(it) == true }) {
        null
    } else {
        McpToolResult.Error(code, message)
    }

/**
 * Gate that looks **only** at the delegated user's own roles — unlike
 * [requireAnyRole] an admin API key does not bypass it, so "ADMIN" has to be
 * listed explicitly when it should grant access. Role names are compared
 * case-insensitively.
 */
fun requireAnyUserRole(
    context: McpExecutionContext,
    vararg roles: String,
    code: String = "AUTHORIZATION_ERROR",
    message: String
): McpToolResult.Error? {
    val held = context.delegatedUserRoles?.mapTo(mutableSetOf()) { it.uppercase() } ?: emptySet<String>()
    return if (roles.any { it.uppercase() in held }) null else McpToolResult.Error(code, message)
}
