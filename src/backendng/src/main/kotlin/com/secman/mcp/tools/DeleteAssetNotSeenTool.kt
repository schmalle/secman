package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.CrowdStrikeCleanupAuditService
import com.secman.service.CrowdStrikeCleanupBasis
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
    override val description = "Delete stale CrowdStrike assets by explicit sensor-contact or import-inactivity policy (ADMIN or SECCHAMPION, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "days" to mapOf(
                "type" to "integer",
                "description" to "Age threshold in days for the selected cleanup basis.",
                "minimum" to 1, "maximum" to 36500
            ),
            "basis" to mapOf("type" to "string", "enum" to listOf("import-inactivity", "sensor-contact"),
                "description" to "Default import-inactivity. Sensor-contact uses Falcon contact, regardless of recent imports."),
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
        requireDelegation(context)?.let { return it }
        if (!context.canManageGrants) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN or SECCHAMPION role required to delete stale assets")
        }

        val days = (arguments["days"] as? Number)?.toInt()
            ?: return McpToolResult.error("INVALID_ARGUMENT", "days is required and must be >= 1")
        if (days !in 1..36500 || (arguments["days"] as? Number)?.toDouble() != days.toDouble()) {
            return McpToolResult.error("INVALID_ARGUMENT", "days must be an integer between 1 and 36500")
        }
        if (arguments["basis"] != null && arguments["basis"] !is String) {
            return McpToolResult.error("INVALID_ARGUMENT", "basis must be import-inactivity or sensor-contact")
        }
        val dryRun = arguments["dryRun"] as? Boolean ?: false
        val includeLegacy = arguments["includeLegacy"] as? Boolean

        return try {
            val result = crowdStrikeCleanupAuditService.run(
                days = days,
                dryRun = dryRun,
                triggeredBy = context.delegatedUserEmail ?: "mcp",
                maxDeletePercent = null,
                includeLegacy = includeLegacy,
                basis = CrowdStrikeCleanupBasis.parse(arguments["basis"] as? String ?: "import-inactivity")
            )

            val response = mapOf(
                "success" to (result.errors.isEmpty() && result.status?.startsWith("ABORTED") != true),
                "basis" to result.basis,
                "cutoffUtc" to result.cutoffUtc?.toString(),
                "retentionDays" to result.retentionDays,
                "exclusions" to result.exclusions,
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
                "candidates" to result.candidates.take(200).map { c ->
                    mapOf(
                        "assetId" to c.assetId,
                        "name" to c.name,
                        "crowdStrikeLastImportedAt" to c.crowdStrikeLastImportedAt?.toString(),
                        "reason" to c.reason.name,
                        "falconLastContact" to c.falconLastContact?.toString(),
                        "lastImportedAt" to c.lastImportedAt?.toString()
                    )
                },
                "errors" to result.errors.map { e ->
                    mapOf("assetId" to e.assetId, "assetName" to e.assetName, "message" to e.message)
                },
                "message" to if (dryRun) {
                    "Dry run: ${result.candidateCount} asset(s) would be deleted (policy ${result.basis}, $days days)"
                } else {
                    "Deleted ${result.deletedCount} asset(s) using ${result.basis}, $days days" +
                        if (result.skippedCount > 0) " (${result.skippedCount} skipped or failed)" else ""
                }
            )
            McpToolResult.success(response)
        } catch (e: IllegalArgumentException) {
            McpToolResult.error("INVALID_ARGUMENT", e.message ?: "Invalid argument")
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Cleanup failed; see backend log for details")
        }
    }
}
