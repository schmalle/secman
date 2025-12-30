package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.RequirementRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

/**
 * MCP tool for deleting all security requirements.
 * Feature: 057-cli-mcp-requirements
 *
 * ADMIN role is required and confirmation flag must be true.
 * Returns the count of deleted requirements.
 */
@Singleton
open class DeleteAllRequirementsTool(
    @Inject private val requirementRepository: RequirementRepository
) : McpTool {

    override val name = "delete_all_requirements"
    override val description = "Delete all requirements from the system (ADMIN only)"
    override val operation = McpOperation.DELETE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "confirm" to mapOf(
                "type" to "boolean",
                "description" to "Must be true to confirm deletion"
            )
        ),
        "required" to listOf("confirm")
    )

    @Transactional
    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Check confirmation flag
        val confirm = arguments["confirm"] as? Boolean
            ?: return McpToolResult.error("VALIDATION_ERROR", "Confirm parameter is required")

        if (!confirm) {
            return McpToolResult.error(
                "CONFIRMATION_REQUIRED",
                "Delete operation requires confirm: true"
            )
        }

        // Check ADMIN role - this is a critical security check
        if (!context.isAdmin) {
            return McpToolResult.error(
                "UNAUTHORIZED",
                "ADMIN role required for delete operation"
            )
        }

        try {
            // Count before delete for response
            val count = requirementRepository.count()

            // Delete all requirements
            requirementRepository.deleteAll()

            val result = mapOf(
                "success" to true,
                "deletedCount" to count,
                "message" to "Deleted $count requirements"
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to delete requirements: ${e.message}")
        }
    }
}
