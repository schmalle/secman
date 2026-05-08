package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.CrowdStrikeCleanupAuditService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for deleting outdated CrowdStrike-imported assets (cleanup of stale assets).
 *
 * Wraps CrowdStrikeCleanupAuditService.run() so manual MCP-triggered cleanups are
 * persisted in crowdstrike_cleanup_run alongside scheduled and HTTP-API runs.
 *
 * ADMIN role is required via User Delegation.
 */
@Singleton
class DeleteOutdatedAssetsTool(
    @Inject private val crowdStrikeCleanupAuditService: CrowdStrikeCleanupAuditService
) : McpTool {

    override val name = "delete_outdated_assets"
    override val description = "Delete CrowdStrike-imported assets not seen in CrowdStrike for more than N days, with cascade deletion (ADMIN only, requires User Delegation). Defaults to dry-run."
    override val operation = McpOperation.DELETE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "days" to mapOf(
                "type" to "number",
                "description" to "Stale-threshold in days (must be > 0). Assets whose CrowdStrike import timestamp (or COALESCE of timestamps when includeLegacy=true) is older than this are candidates for deletion."
            ),
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "If true, returns matching candidates without deleting anything. Default: true."
            ),
            "includeLegacy" to mapOf(
                "type" to "boolean",
                "description" to "Feature 087: also include legacy CrowdStrike-owned assets without an import timestamp. Default: use the configured server-side default (secman.crowdstrike.cleanup.include-legacy)."
            )
        ),
        "required" to listOf("days")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to delete outdated assets"
            )
        }

        val days = (arguments["days"] as? Number)?.toInt()
        if (days == null || days <= 0) {
            return McpToolResult.error("VALIDATION_ERROR", "days is required and must be a positive integer")
        }

        // Default to dryRun = true for safety (destructive operation).
        val dryRun = arguments["dryRun"] as? Boolean ?: true
        val includeLegacy = arguments["includeLegacy"] as? Boolean

        val triggeredBy = context.delegatedUserEmail ?: "mcp-system"

        return try {
            val response = crowdStrikeCleanupAuditService.run(
                days = days,
                dryRun = dryRun,
                triggeredBy = triggeredBy,
                maxDeletePercent = null,
                includeLegacy = includeLegacy
            )

            val payload = mapOf(
                "days" to response.days,
                "cutoff" to response.cutoff.toString(),
                "dryRun" to response.dryRun,
                "candidateCount" to response.candidateCount,
                "deletedCount" to response.deletedCount,
                "skippedCount" to response.skippedCount,
                "legacyCandidateCount" to response.legacyCandidateCount,
                "legacyDeletedCount" to response.legacyDeletedCount,
                "candidates" to response.candidates.map {
                    mapOf(
                        "assetId" to it.assetId,
                        "name" to it.name,
                        "crowdStrikeLastImportedAt" to it.crowdStrikeLastImportedAt?.toString(),
                        "reason" to it.reason.name
                    )
                },
                "errors" to response.errors.map {
                    mapOf(
                        "assetId" to it.assetId,
                        "assetName" to it.assetName,
                        "message" to it.message
                    )
                },
                "message" to if (response.dryRun) {
                    "Dry run: ${response.candidateCount} candidate(s) would be deleted (cutoff=${response.cutoff})"
                } else {
                    "Deleted ${response.deletedCount}/${response.candidateCount} candidate(s) (skipped=${response.skippedCount})"
                }
            )

            McpToolResult.success(payload)

        } catch (e: IllegalArgumentException) {
            McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid cleanup request")
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Failed to delete outdated assets: ${e.message}")
        }
    }
}
