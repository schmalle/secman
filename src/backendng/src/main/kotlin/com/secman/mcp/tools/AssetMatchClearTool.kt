package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AssetMatchClearService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool that reconciles AWS assets in secman against an authoritative resource snapshot.
 *
 * Mirrors the core delete path of the CLI command `asset-match-clear` (without S3 download).
 * The caller supplies the list of known account IDs and resource IDs directly; the backend
 * deletes every secman asset whose cloudAccountId is in accountIds and whose cloudInstanceId
 * is NOT in resourceIds.
 *
 * ADMIN role is required via User Delegation.
 */
@Singleton
class AssetMatchClearTool(
    @Inject private val assetMatchClearService: AssetMatchClearService
) : McpTool {

    override val name = "asset_match_clear"
    override val description = "Delete AWS assets in secman whose cloudInstanceId is missing from a supplied resource snapshot (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "accountIds" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "AWS account IDs covered by the snapshot. Only assets in these accounts are considered for deletion."
            ),
            "resourceIds" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "Currently existing AWS resource/instance IDs from the snapshot. Assets NOT in this list will be deleted."
            ),
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "Preview matching assets without deleting them. Default: false"
            ),
            "maxDeletePercent" to mapOf(
                "type" to "number",
                "description" to "Abort if proposed deletions exceed N% of scoped assets. Default: 25. Set 0 to disable.",
                "minimum" to 0,
                "maximum" to 100
            ),
            "strict" to mapOf(
                "type" to "boolean",
                "description" to "Treat the snapshot as globally authoritative across all secman AWS assets (not just snapshot-covered accounts). Default: false"
            )
        ),
        "required" to listOf("accountIds", "resourceIds")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error("DELEGATION_REQUIRED", "User Delegation must be enabled to use this tool")
        }
        if (!context.isAdmin) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN role required to run asset match-clear")
        }

        @Suppress("UNCHECKED_CAST")
        val accountIds = (arguments["accountIds"] as? List<*>)?.filterIsInstance<String>()
            ?: return McpToolResult.error("INVALID_ARGUMENT", "accountIds is required and must be a list of strings")
        @Suppress("UNCHECKED_CAST")
        val resourceIds = (arguments["resourceIds"] as? List<*>)?.filterIsInstance<String>()
            ?: return McpToolResult.error("INVALID_ARGUMENT", "resourceIds is required and must be a list of strings")

        if (accountIds.isEmpty()) {
            return McpToolResult.error("INVALID_ARGUMENT", "accountIds must not be empty")
        }

        val dryRun = arguments["dryRun"] as? Boolean ?: false
        val maxDeletePercent = (arguments["maxDeletePercent"] as? Number)?.toInt() ?: 25
        val strict = arguments["strict"] as? Boolean ?: false

        return try {
            val result = assetMatchClearService.clear(
                accountIds = accountIds,
                resourceIds = resourceIds,
                dryRun = dryRun,
                username = context.delegatedUserEmail ?: "mcp",
                maxDeletePercent = if (maxDeletePercent == 0) null else maxDeletePercent,
                strict = strict
            )

            val response = mapOf(
                "success" to true,
                "dryRun" to result.dryRun,
                "scopeMode" to result.scopeMode,
                "snapshotAccountCount" to result.snapshotAccountCount,
                "snapshotResourceCount" to result.snapshotResourceCount,
                "scopedAssetCount" to result.scopedAssetCount,
                "uncoveredAccountCount" to result.uncoveredAccountCount,
                "uncoveredAssetCount" to result.uncoveredAssetCount,
                "candidateCount" to result.candidateCount,
                "deletedCount" to result.deletedCount,
                "skippedCount" to result.skippedCount,
                "status" to result.status,
                "safetyBrakeTripped" to result.safetyBrakeTripped,
                "safetyBrakePercent" to result.safetyBrakePercent,
                "candidates" to result.candidates.map { c ->
                    mapOf(
                        "assetId" to c.assetId,
                        "name" to c.name,
                        "cloudAccountId" to c.cloudAccountId,
                        "cloudInstanceId" to c.cloudInstanceId
                    )
                },
                "errors" to result.errors.map { e ->
                    mapOf("assetId" to e.assetId, "assetName" to e.assetName, "message" to e.message)
                },
                "message" to when {
                    result.safetyBrakeTripped -> "Safety brake tripped — no assets deleted (proposed deletions exceeded ${result.safetyBrakePercent}% limit)"
                    dryRun -> "Dry run: ${result.candidateCount} asset(s) would be deleted"
                    else -> "Deleted ${result.deletedCount} asset(s) not in the resource snapshot" +
                        if (result.skippedCount > 0) " (${result.skippedCount} failed)" else ""
                }
            )
            McpToolResult.success(response)
        } catch (e: AssetMatchClearService.EmptySnapshotException) {
            McpToolResult.error("EMPTY_SNAPSHOT", e.message ?: "Empty snapshot rejected")
        } catch (e: IllegalArgumentException) {
            McpToolResult.error("INVALID_ARGUMENT", e.message ?: "Invalid argument")
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Asset match-clear failed: ${e.message}")
        }
    }
}
