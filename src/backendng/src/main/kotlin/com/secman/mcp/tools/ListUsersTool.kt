package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.UserRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for listing all users in the system.
 * Feature: 060-mcp-list-users
 *
 * ADMIN role is required via User Delegation.
 * Returns all users with their core attributes (excluding password hash).
 *
 * Spec reference: spec.md FR-001 through FR-011
 * User Stories: US1 (List Users), US2 (Deny Non-Admin)
 */
@Singleton
class ListUsersTool(
    @Inject private val userRepository: UserRepository
) : McpTool {

    override val name = "list_users"
    override val description = "List all users in the system (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // FR-002, FR-004: Require User Delegation
        // T002: Delegation check - cannot verify admin role without knowing the user
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // FR-003, FR-005, FR-010: Require ADMIN role
        // T003: Admin role check - protect user data privacy
        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to list users"
            )
        }

        try {
            // T004: Retrieve all users from database
            val users = userRepository.findAll()

            // T005: Map User entity to response (FR-006, FR-007, FR-008)
            // Exclude passwordHash for security
            val result = mapOf(
                "users" to users.map { user ->
                    mapOf(
                        "id" to user.id,
                        "username" to user.username,
                        "email" to user.email,
                        "roles" to user.roles.map { it.name },
                        "authSource" to user.authSource.name,
                        "mfaEnabled" to user.mfaEnabled,
                        "createdAt" to user.createdAt?.toString(),
                        "lastLogin" to user.lastLogin?.toString()
                    )
                },
                // T006: FR-009 - Include total count in metadata
                "totalCount" to users.size
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve users: ${e.message}")
        }
    }
}
