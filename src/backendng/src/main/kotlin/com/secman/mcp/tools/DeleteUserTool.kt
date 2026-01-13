package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.UserDeletionValidator
import com.secman.service.UserService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for deleting users from the system.
 *
 * ADMIN role is required via User Delegation.
 * Validates deletion constraints before removing the user.
 *
 * Security constraints:
 * - Requires User Delegation to be enabled
 * - Requires ADMIN role
 * - Prevents self-deletion (delegated user cannot delete themselves)
 * - Validates no blocking references (demands, risk assessments, etc.)
 * - Protects the last administrator from being deleted
 */
@Singleton
class DeleteUserTool(
    @Inject private val userService: UserService,
    @Inject private val userDeletionValidator: UserDeletionValidator
) : McpTool {

    override val name = "delete_user"
    override val description = "Delete a user from the system (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.DELETE

    override val inputSchema = mapOf(
        "type" to "object",
        "required" to listOf("userId"),
        "properties" to mapOf(
            "userId" to mapOf(
                "type" to "number",
                "description" to "The ID of the user to delete"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Require User Delegation - cannot verify admin role without knowing the user
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // Require ADMIN role - protect user management
        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to delete users"
            )
        }

        // Parse and validate userId parameter
        val userId = (arguments["userId"] as? Number)?.toLong()
            ?: return McpToolResult.error(
                "VALIDATION_ERROR",
                "userId is required and must be a number"
            )

        // Prevent self-deletion - the delegated user cannot delete themselves
        if (userId == context.delegatedUserId) {
            return McpToolResult.error(
                "SELF_DELETION_FORBIDDEN",
                "You cannot delete your own user account"
            )
        }

        // Check if user exists
        val userToDelete = userService.getUserById(userId)
            ?: return McpToolResult.error(
                "USER_NOT_FOUND",
                "User with ID $userId not found"
            )

        try {
            // Validate deletion constraints (blocking references, last admin, etc.)
            val validationResult = userDeletionValidator.validateUserDeletion(userId)

            if (!validationResult.canDelete) {
                return McpToolResult.error(
                    "DELETION_BLOCKED",
                    validationResult.message,
                    mapOf("blockingReferences" to validationResult.blockingReferences.map { ref ->
                        mapOf(
                            "entityType" to ref.entityType,
                            "count" to ref.count,
                            "role" to ref.role,
                            "details" to ref.details
                        )
                    })
                )
            }

            // Capture user info before deletion for response
            val deletedUserInfo = mapOf(
                "id" to userToDelete.id,
                "username" to userToDelete.username,
                "email" to userToDelete.email,
                "roles" to userToDelete.roles.map { it.name }
            )

            // Perform deletion
            val deleted = userService.deleteUser(userId)

            if (!deleted) {
                return McpToolResult.error(
                    "DELETION_FAILED",
                    "Failed to delete user with ID $userId"
                )
            }

            return McpToolResult.success(
                mapOf(
                    "deleted" to true,
                    "user" to deletedUserInfo,
                    "message" to "User '${userToDelete.username}' (ID: $userId) has been deleted successfully"
                )
            )

        } catch (e: Exception) {
            return McpToolResult.error(
                "EXECUTION_ERROR",
                "Failed to delete user: ${e.message}"
            )
        }
    }
}
