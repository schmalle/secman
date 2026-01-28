package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.ReportService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for retrieving risk mitigation status report.
 *
 * Returns:
 * - Summary: total open risks, overdue risks, unassigned risks
 * - Detailed risk list with mitigation status, overdue flag, likelihood/impact scores
 *
 * Feature: 069-mcp-lense-reports
 * Task: T012
 * User Story: US2 - Risk Mitigation Status (P1)
 * Spec reference: FR-002, FR-005, FR-006, FR-007
 *
 * Access Control:
 * - Uses context.getFilterableAssetIds() for row-level filtering
 * - ADMIN/non-delegated: sees all data (null = no filtering)
 * - Delegated user: sees only accessible assets
 */
@Singleton
class GetRiskMitigationStatusTool(
    @Inject private val reportService: ReportService
) : McpTool {

    override val name = "get_risk_mitigation_status"
    override val description = "Retrieve risk mitigation status report with open risks summary and detailed risk list including overdue identification"
    override val operation = McpOperation.READ

    companion object {
        private val VALID_STATUSES = listOf("OPEN", "MITIGATED", "CLOSED")
    }

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "status" to mapOf(
                "type" to "string",
                "description" to "Filter by risk status (optional)",
                "enum" to VALID_STATUSES
            )
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Extract and validate status filter
        val statusFilter = arguments["status"] as? String
        if (statusFilter != null && statusFilter !in VALID_STATUSES) {
            return McpToolResult.error(
                "INVALID_STATUS",
                "Invalid status filter: '$statusFilter'. Must be one of: ${VALID_STATUSES.joinToString(", ")}"
            )
        }

        try {
            // Get accessible asset IDs for access control filtering
            val accessibleAssetIds = context.getFilterableAssetIds()

            // Get risk mitigation status from service
            val mitigationStatus = reportService.getRiskMitigationStatus(accessibleAssetIds, statusFilter)

            // Map to response format
            val result = mapOf(
                "summary" to mapOf(
                    "totalOpenRisks" to mitigationStatus.summary.totalOpenRisks,
                    "overdueRisks" to mitigationStatus.summary.overdueRisks,
                    "unassignedRisks" to mitigationStatus.summary.unassignedRisks
                ),
                "risks" to mitigationStatus.risks.map { risk ->
                    mapOf(
                        "id" to risk.id,
                        "name" to risk.name,
                        "description" to risk.description,
                        "assetName" to risk.assetName,
                        "riskLevel" to risk.riskLevel,
                        "riskLevelText" to risk.riskLevelText,
                        "status" to risk.status,
                        "owner" to risk.owner,
                        "severity" to risk.severity,
                        "deadline" to risk.deadline,
                        "isOverdue" to risk.isOverdue,
                        "likelihood" to risk.likelihood,
                        "impact" to risk.impact
                    )
                }
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve risk mitigation status: ${e.message}")
        }
    }
}
