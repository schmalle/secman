package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.WorkgroupService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for deleting a workgroup.
 * Feature: 074-mcp-e2e-test
 *
 * ADMIN role is required via User Delegation.
 * Cascade deletion of user and asset associations is handled by JPA.
 *
 * Input parameters:
 * - workgroupId (required): ID of the workgroup to delete
 */
@Singleton
class DeleteWorkgroupTool(
    @Inject private val workgroupService: WorkgroupService
) : McpTool {

    override val name = "delete_workgroup"
    override val description = "Delete a workgroup by ID (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.DELETE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "workgroupId" to mapOf(
                "type" to "number",
                "description" to "The ID of the workgroup to delete"
            )
        ),
        "required" to listOf("workgroupId")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Require User Delegation
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
                "ADMIN role required to delete workgroups"
            )
        }

        // Extract and validate required parameters
        val workgroupId = (arguments["workgroupId"] as? Number)?.toLong()
        if (workgroupId == null) {
            return McpToolResult.error("VALIDATION_ERROR", "workgroupId is required and must be a valid number")
        }

        try {
            // Get workgroup info before deletion
            val workgroup = workgroupService.getWorkgroupById(workgroupId)
            val workgroupName = workgroup.name

            workgroupService.deleteWorkgroup(workgroupId)

            val result = mapOf(
                "id" to workgroupId,
                "name" to workgroupName,
                "message" to "Workgroup '$workgroupName' (ID: $workgroupId) deleted successfully"
            )

            return McpToolResult.success(result)

        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "Workgroup not found")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to delete workgroup: ${e.message}")
        }
    }
}
