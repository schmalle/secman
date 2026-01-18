package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AssetBulkDeleteService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for deleting all assets from the system.
 * Feature: 063-e2e-vuln-exception
 *
 * ADMIN role is required via User Delegation and confirmation flag must be true.
 * Returns the count of deleted assets and cascade-deleted entities.
 *
 * Input parameters:
 * - confirm (required): Must be true to confirm deletion
 *
 * Output:
 * - deletedAssets: Number of assets deleted
 * - deletedVulnerabilities: Number of vulnerabilities cascade-deleted
 * - deletedScanResults: Number of scan results cascade-deleted
 * - deletedScanPorts: Number of scan ports cascade-deleted
 */
@Singleton
class DeleteAllAssetsTool(
    @Inject private val assetBulkDeleteService: AssetBulkDeleteService
) : McpTool {

    override val name = "delete_all_assets"
    override val description = "Delete all assets from the system with cascade deletion of vulnerabilities, scan results, and ports (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.DELETE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "confirm" to mapOf(
                "type" to "boolean",
                "description" to "Must be true to confirm deletion. This is a destructive operation that cannot be undone."
            )
        ),
        "required" to listOf("confirm")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Require User Delegation for audit trail
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // Require ADMIN role - this is a critical destructive operation
        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to delete all assets"
            )
        }

        // Check confirmation flag
        val confirm = arguments["confirm"] as? Boolean
            ?: return McpToolResult.error("VALIDATION_ERROR", "Confirm parameter is required")

        if (!confirm) {
            return McpToolResult.error(
                "CONFIRMATION_REQUIRED",
                "Delete operation requires confirm: true. This will delete ALL assets and their associated data."
            )
        }

        try {
            // Execute bulk delete
            val result = assetBulkDeleteService.deleteAllAssets()

            val response = mapOf(
                "success" to true,
                "deletedAssets" to result.deletedAssets,
                "deletedVulnerabilities" to result.deletedVulnerabilities,
                "deletedScanResults" to result.deletedScanResults,
                "message" to "Deleted ${result.deletedAssets} assets with ${result.deletedVulnerabilities} vulnerabilities and ${result.deletedScanResults} scan results"
            )

            return McpToolResult.success(response)

        } catch (e: AssetBulkDeleteService.ConcurrentOperationException) {
            return McpToolResult.error(
                "CONCURRENT_OPERATION",
                "Another bulk delete operation is already in progress. Please wait and try again."
            )
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to delete assets: ${e.message}")
        }
    }
}
