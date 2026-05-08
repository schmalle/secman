package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.CrowdStrikeVulnerabilityImportService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * MCP tool exposing the timestamp and metadata of the most recent CrowdStrike
 * vulnerability import. Mirrors the controller endpoint `/api/crowdstrike/servers/import/latest`.
 *
 * Requires ADMIN or VULN role via User Delegation, matching the REST controller's
 * `@Secured("ADMIN", "VULN")` guard.
 */
@Singleton
class GetCrowdStrikeLastImportTool(
    @Inject private val importService: CrowdStrikeVulnerabilityImportService
) : McpTool {

    private val logger = LoggerFactory.getLogger(GetCrowdStrikeLastImportTool::class.java)

    override val name = "get_crowdstrike_last_import"
    override val description = "Get the timestamp and metadata of the most recent CrowdStrike vulnerability import (ADMIN or VULN only, requires User Delegation). Returns null when no import has ever run."
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>(),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        val hasRequiredRole = context.isAdmin ||
            context.delegatedUserRoles?.contains("VULN") == true

        if (!hasRequiredRole) {
            return McpToolResult.error(
                "ROLE_REQUIRED",
                "ADMIN or VULN role required to read CrowdStrike import status"
            )
        }

        return try {
            val status = importService.getLatestImportStatus()

            val response = if (status == null) {
                mapOf(
                    "lastImportAt" to null,
                    "message" to "No CrowdStrike import has ever run"
                )
            } else {
                mapOf(
                    "lastImportAt" to status.importedAt.toString(),
                    "importedBy" to status.importedBy,
                    "serversProcessed" to status.serversProcessed,
                    "serversCreated" to status.serversCreated,
                    "serversUpdated" to status.serversUpdated,
                    "vulnerabilitiesImported" to status.vulnerabilitiesImported,
                    "vulnerabilitiesSkipped" to status.vulnerabilitiesSkipped,
                    "vulnerabilitiesWithPatchDate" to status.vulnerabilitiesWithPatchDate,
                    "errorCount" to status.errorCount
                )
            }

            McpToolResult.success(response)
        } catch (e: Exception) {
            logger.error("Failed to retrieve CrowdStrike last import status via MCP", e)
            McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve last import: ${e.message}")
        }
    }
}
