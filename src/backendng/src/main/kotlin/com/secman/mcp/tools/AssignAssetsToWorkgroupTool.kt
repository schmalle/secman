package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.WorkgroupService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for assigning assets to a workgroup.
 * Feature: 074-mcp-e2e-test
 *
 * ADMIN role is required via User Delegation.
 *
 * Input parameters:
 * - workgroupId (required): ID of the target workgroup
 * - assetIds (required): List of asset IDs to assign
 */
@Singleton
class AssignAssetsToWorkgroupTool(
    @Inject private val workgroupService: WorkgroupService
) : McpTool {

    override val name = "assign_assets_to_workgroup"
    override val description = "Assign one or more assets to a workgroup (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "workgroupId" to mapOf(
                "type" to "number",
                "description" to "The ID of the target workgroup"
            ),
            "assetIds" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "number"),
                "description" to "List of asset IDs to assign to the workgroup"
            )
        ),
        "required" to listOf("workgroupId", "assetIds")
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
                "ADMIN role required to assign assets to workgroups"
            )
        }

        // Extract and validate required parameters
        val workgroupId = (arguments["workgroupId"] as? Number)?.toLong()
        if (workgroupId == null) {
            return McpToolResult.error("VALIDATION_ERROR", "workgroupId is required and must be a valid number")
        }

        @Suppress("UNCHECKED_CAST")
        val assetIdsRaw = arguments["assetIds"] as? List<*>
        if (assetIdsRaw.isNullOrEmpty()) {
            return McpToolResult.error("VALIDATION_ERROR", "assetIds is required and cannot be empty")
        }

        val assetIds = try {
            assetIdsRaw.map { (it as Number).toLong() }
        } catch (e: Exception) {
            return McpToolResult.error("VALIDATION_ERROR", "assetIds must be a list of valid numbers")
        }

        try {
            workgroupService.assignAssetsToWorkgroup(workgroupId, assetIds)

            val result = mapOf(
                "workgroupId" to workgroupId,
                "assignedCount" to assetIds.size,
                "assetIds" to assetIds,
                "message" to "${assetIds.size} asset(s) assigned to workgroup $workgroupId"
            )

            return McpToolResult.success(result)

        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "Workgroup or asset not found")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to assign assets to workgroup: ${e.message}")
        }
    }
}
