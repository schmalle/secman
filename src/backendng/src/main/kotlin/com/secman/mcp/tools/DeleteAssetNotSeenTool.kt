package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.CrowdStrikeCleanupAuditService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool that deletes CrowdStrike-imported assets not seen in CrowdStrike for N days.
 *
 * Mirrors the CLI command `delete-asset-not-seen`.
 * ADMIN role is required via User Delegation.
 */
@Singleton
class DeleteAssetNotSeenTool(
    @Inject private val crowdStrikeCleanupAuditService: CrowdStrikeCleanupAuditService
) : McpTool {

    override val name = "delete_asset_not_seen"
    override val description = "Delete CrowdStrike-imported assets not seen in CrowdStrike for more than N days (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "days" to mapOf(
                "type" to "number",
                "description" to "Days since last CrowdStrike import. Assets with no import in this window are deleted.",
                "minimum" to 1
            ),
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "Preview matching assets without deleting them. Default: false"
            ),
            "includeLegacy" to mapOf(
                "type" to "boolean",
                "description" to "Also clean up legacy CrowdStrike assets with no import timestamp (rule B, Feature 087). Default: uses server-configured default"
            )
        ),
        "required" to listOf("days")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error("DELEGATION_REQUIRED", "User Delegation must be enabled to use this tool")
        }
        if (!context.isAdmin) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN role required to delete stale assets")
        }

        val days = (arguments["days"] as? Number)?.toInt()
            ?: return McpToolResult.error("INVALID_ARGUMENT", "days is required and must be >= 1")
        if (days < 1) {
            return McpToolResult.error("INVALID_ARGUMENT", "days must be >= 1")
        }
        val dryRun = arguments["dryRun"] as? Boolean ?: false
        val includeLegacy = arguments["includeLegacy"] as? Boolean

        return try {
            val result = crowdStrikeCleanupAuditService.run(
                days = days,
                dryRun = dryRun,
                triggeredBy = context.delegatedUserEmail ?: "mcp",
                maxDeletePercent = null,
                includeLegacy = includeLegacy
            )

            val response = mapOf(
                "success" to true,
                "days" to result.days,
                "cutoff" to result.cutoff.toString(),
                "dryRun" to result.dryRun,
                "candidateCount" to result.candidateCount,
                "deletedCount" to result.deletedCount,
                "skippedCount" to result.skippedCount,
                "legacyCandidateCount" to result.legacyCandidateCount,
                "legacyDeletedCount" to result.legacyDeletedCount,
                "status" to (result.status ?: if (dryRun) "DRY_RUN" else "SUCCESS"),
                "runId" to result.runId,
                "candidates" to result.candidates.map { c ->
                    mapOf(
                        "assetId" to c.assetId,
                        "name" to c.name,
                        "crowdStrikeLastImportedAt" to c.crowdStrikeLastImportedAt?.toString(),
                        "reason" to c.reason.name
                    )
                },
                "errors" to result.errors.map { e ->
                    mapOf("assetId" to e.assetId, "assetName" to e.assetName, "message" to e.message)
                },
                "message" to if (dryRun) {
                    "Dry run: ${result.candidateCount} asset(s) would be deleted (not seen in $days days)"
                } else {
                    "Deleted ${result.deletedCount} asset(s) not seen in $days days" +
                        if (result.skippedCount > 0) " (${result.skippedCount} failed)" else ""
                }
            )
            McpToolResult.success(response)
        } catch (e: IllegalArgumentException) {
            McpToolResult.error("INVALID_ARGUMENT", e.message ?: "Invalid argument")
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Cleanup failed: ${e.message}")
        }
    }
}
