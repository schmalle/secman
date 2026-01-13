package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.UserRepository
import com.secman.service.UserDeletionValidator
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for deleting a user.
 * Feature: add-user-deletion-admin
 *
 * ADMIN role is required via User Delegation.
 * Validates that the user can be safely deleted (no blocking references, not last admin).
 *
 * Input parameters:
 * - userId (required): ID of the user to delete
 */
@Singleton
class DeleteUserTool(
    @Inject private val userRepository: UserRepository,
    @Inject private val userDeletionValidator: UserDeletionValidator
) : McpTool {

    override val name = "delete_user"
    override val description = "Delete a user by ID (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.DELETE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "userId" to mapOf(
                "type" to "number",
                "description" to "The ID of the user to delete"
            )
        ),
        "required" to listOf("userId")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Require User Delegation - cannot verify admin role without knowing the user
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // Require ADMIN role
        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to delete users"
            )
        }

        // Extract and validate required parameters
        val userId = (arguments["userId"] as? Number)?.toLong()

        if (userId == null) {
            return McpToolResult.error("VALIDATION_ERROR", "userId is required and must be a valid number")
        }

        // Check if user exists
        val userOptional = userRepository.findById(userId)
        if (userOptional.isEmpty) {
            return McpToolResult.error("NOT_FOUND", "User with ID $userId not found")
        }

        val user = userOptional.get()

        // Prevent self-deletion
        if (context.delegatedUserId == userId) {
            return McpToolResult.error(
                "SELF_DELETION_NOT_ALLOWED",
                "Cannot delete your own user account"
            )
        }

        // Validate user deletion using UserDeletionValidator
        val validationResult = userDeletionValidator.validateUserDeletion(userId)

        if (!validationResult.canDelete) {
            val blockingDetails = validationResult.blockingReferences.map { ref ->
                mapOf(
                    "entityType" to ref.entityType,
                    "count" to ref.count,
                    "role" to ref.role,
                    "details" to ref.details
                )
            }

            return McpToolResult.error(
                "DELETION_BLOCKED",
                validationResult.message,
                mapOf("blockingReferences" to blockingDetails)
            )
        }

        try {
            // Store user info for response before deletion
            val deletedUserInfo = mapOf(
                "id" to user.id,
                "username" to user.username,
                "email" to user.email,
                "roles" to user.roles.map { it.name }
            )

            // Delete the user
            userRepository.deleteById(userId)

            val result = mapOf(
                "deletedUser" to deletedUserInfo,
                "message" to "User '${user.username}' (ID: $userId) deleted successfully"
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to delete user: ${e.message}")
        }
    }
}
