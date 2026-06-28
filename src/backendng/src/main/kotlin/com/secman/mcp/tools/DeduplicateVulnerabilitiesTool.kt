package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.VulnerabilityDeduplicationService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool that removes duplicate vulnerability records from the database.
 *
 * Mirrors the CLI command `deduplicate-vulnerabilities`.
 * ADMIN role is required via User Delegation.
 *
 * For each asset, keeps the oldest record (lowest primary key) when the same
 * (CVE ID, product) combination appears more than once.
 */
@Singleton
class DeduplicateVulnerabilitiesTool(
    @Inject private val vulnerabilityDeduplicationService: VulnerabilityDeduplicationService
) : McpTool {

    override val name = "deduplicate_vulnerabilities"
    override val description = "Remove duplicate vulnerability records from the database, keeping the oldest entry per (CVE ID, product) per asset (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>(),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error("DELEGATION_REQUIRED", "User Delegation must be enabled to use this tool")
        }
        if (!context.isAdmin) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN role required to deduplicate vulnerabilities")
        }

        return try {
            val result = vulnerabilityDeduplicationService.deduplicateAll()

            val response = mapOf(
                "success" to true,
                "totalDuplicatesRemoved" to result.totalDuplicatesRemoved,
                "assetsAffected" to result.assetsAffected,
                "details" to result.details.map { d ->
                    mapOf(
                        "assetId" to d.assetId,
                        "assetName" to (d.assetName ?: ""),
                        "duplicatesRemoved" to d.duplicatesRemoved,
                        "duplicateKeys" to d.duplicateKeys
                    )
                },
                "message" to if (result.totalDuplicatesRemoved == 0) {
                    "No duplicate vulnerabilities found. Database is clean."
                } else {
                    "Removed ${result.totalDuplicatesRemoved} duplicate vulnerability record(s) across ${result.assetsAffected} asset(s)"
                }
            )
            McpToolResult.success(response)
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Deduplication failed: ${e.message}")
        }
    }
}
