package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AssetCascadeDeleteService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for deleting a specific asset by ID.
 * Feature: 074-mcp-e2e-test
 *
 * ADMIN role is required via User Delegation.
 * Uses AssetCascadeDeleteService for cascade deletion of:
 * - Vulnerability exception requests
 * - ASSET-type vulnerability exceptions
 * - Vulnerabilities
 * - Asset
 *
 * Input parameters:
 * - assetId (required): ID of the asset to delete
 * - forceTimeout (optional): Force deletion even if it may exceed timeout (default: false)
 */
@Singleton
class DeleteAssetTool(
    @Inject private val assetCascadeDeleteService: AssetCascadeDeleteService
) : McpTool {

    override val name = "delete_asset"
    override val description = "Delete a specific asset by ID with cascade deletion (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.DELETE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "assetId" to mapOf(
                "type" to "number",
                "description" to "The ID of the asset to delete"
            ),
            "forceTimeout" to mapOf(
                "type" to "boolean",
                "description" to "Force deletion even if it may exceed timeout (default: false)"
            )
        ),
        "required" to listOf("assetId")
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
                "ADMIN role required to delete assets"
            )
        }

        // Extract and validate required parameters
        val assetId = (arguments["assetId"] as? Number)?.toLong()
        if (assetId == null) {
            return McpToolResult.error("VALIDATION_ERROR", "assetId is required and must be a valid number")
        }

        val forceTimeout = arguments["forceTimeout"] as? Boolean ?: false

        // Get username from delegation context
        val username = context.delegatedUserEmail ?: "mcp-system"

        try {
            val deletionResult = assetCascadeDeleteService.deleteAsset(
                assetId = assetId,
                username = username,
                forceTimeout = forceTimeout
            )

            val result = mapOf(
                "id" to deletionResult.assetId,
                "name" to deletionResult.assetName,
                "vulnerabilitiesDeleted" to deletionResult.deletedVulnerabilities,
                "exceptionsDeleted" to deletionResult.deletedExceptions,
                "requestsDeleted" to deletionResult.deletedRequests,
                "auditLogId" to deletionResult.auditLogId,
                "message" to "Asset '${deletionResult.assetName}' (ID: $assetId) deleted successfully"
            )

            return McpToolResult.success(result)

        } catch (e: AssetCascadeDeleteService.AssetNotFoundException) {
            return McpToolResult.error("NOT_FOUND", "Asset with ID $assetId not found")
        } catch (e: AssetCascadeDeleteService.TimeoutWarningException) {
            return McpToolResult.error(
                "TIMEOUT_WARNING",
                "Estimated deletion time (${e.estimatedSeconds}s) exceeds timeout. Use forceTimeout=true to proceed anyway.",
                mapOf(
                    "assetId" to e.assetId,
                    "assetName" to e.assetName,
                    "estimatedSeconds" to e.estimatedSeconds
                )
            )
        } catch (e: jakarta.persistence.PessimisticLockException) {
            return McpToolResult.error(
                "LOCKED",
                "Asset is currently being modified by another operation. Please try again."
            )
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to delete asset: ${e.message}")
        }
    }
}
