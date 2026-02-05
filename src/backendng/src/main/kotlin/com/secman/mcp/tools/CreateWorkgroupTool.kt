package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.WorkgroupService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for creating a new workgroup.
 * Feature: 074-mcp-e2e-test
 *
 * ADMIN role is required via User Delegation.
 *
 * Input parameters:
 * - name (required): Unique name for the workgroup
 * - description (optional): Description of the workgroup
 */
@Singleton
class CreateWorkgroupTool(
    @Inject private val workgroupService: WorkgroupService
) : McpTool {

    override val name = "create_workgroup"
    override val description = "Create a new workgroup (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf(
                "type" to "string",
                "description" to "Unique name for the workgroup (1-100 characters)"
            ),
            "description" to mapOf(
                "type" to "string",
                "description" to "Optional description of the workgroup (max 512 characters)"
            )
        ),
        "required" to listOf("name")
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
                "ADMIN role required to create workgroups"
            )
        }

        // Extract and validate required parameters
        val name = (arguments["name"] as? String)?.trim()
        if (name.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "Workgroup name is required")
        }

        if (name.length > 100) {
            return McpToolResult.error("VALIDATION_ERROR", "Workgroup name must be 100 characters or less")
        }

        val description = (arguments["description"] as? String)?.trim()
        if (description != null && description.length > 512) {
            return McpToolResult.error("VALIDATION_ERROR", "Description must be 512 characters or less")
        }

        try {
            val workgroup = workgroupService.createWorkgroup(
                name = name,
                description = description
            )

            val result = mapOf(
                "id" to workgroup.id,
                "name" to workgroup.name,
                "description" to workgroup.description,
                "message" to "Workgroup '${workgroup.name}' created successfully"
            )

            return McpToolResult.success(result)

        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("DUPLICATE_ERROR", e.message ?: "Workgroup creation failed")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to create workgroup: ${e.message}")
        }
    }
}
