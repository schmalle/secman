package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.ReportService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for retrieving risk assessment summary report.
 *
 * Returns aggregated statistics including:
 * - Assessment summary: total count and status breakdown
 * - Risk summary: total count, status breakdown, and risk level breakdown
 * - Asset coverage: total assets, assets with assessments, coverage percentage
 * - Recent assessments: 10 most recent assessments
 * - High priority risks: risks with level >= 3 (High, Critical)
 *
 * Feature: 069-mcp-lense-reports
 * Task: T009
 * User Story: US1 - Risk Assessment Summary (P1)
 * Spec reference: FR-001, FR-005, FR-006
 *
 * Access Control:
 * - Uses context.getFilterableAssetIds() for row-level filtering
 * - ADMIN/non-delegated: sees all data (null = no filtering)
 * - Delegated user: sees only accessible assets
 */
@Singleton
class GetRiskAssessmentSummaryTool(
    @Inject private val reportService: ReportService
) : McpTool {

    override val name = "get_risk_assessment_summary"
    override val description = "Retrieve risk assessment summary report with assessment metrics, risk breakdowns, asset coverage, recent assessments, and high-priority risks"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>(),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        try {
            // Get accessible asset IDs for access control filtering
            val accessibleAssetIds = context.getFilterableAssetIds()

            // Get risk assessment summary from service
            val summary = reportService.getRiskAssessmentSummary(accessibleAssetIds)

            // Map to response format
            val result = mapOf(
                "assessmentSummary" to mapOf(
                    "total" to summary.assessmentSummary.total,
                    "statusBreakdown" to summary.assessmentSummary.statusBreakdown
                ),
                "riskSummary" to mapOf(
                    "total" to summary.riskSummary.total,
                    "statusBreakdown" to summary.riskSummary.statusBreakdown,
                    "riskLevelBreakdown" to summary.riskSummary.riskLevelBreakdown
                ),
                "assetCoverage" to mapOf(
                    "totalAssets" to summary.assetCoverage.totalAssets,
                    "assetsWithAssessments" to summary.assetCoverage.assetsWithAssessments,
                    "coveragePercentage" to summary.assetCoverage.coveragePercentage
                ),
                "recentAssessments" to summary.recentAssessments.map { assessment ->
                    mapOf(
                        "id" to assessment.id,
                        "assetName" to assessment.assetName,
                        "status" to assessment.status,
                        "assessor" to assessment.assessor,
                        "startDate" to assessment.startDate,
                        "endDate" to assessment.endDate
                    )
                },
                "highPriorityRisks" to summary.highPriorityRisks.map { risk ->
                    mapOf(
                        "id" to risk.id,
                        "name" to risk.name,
                        "assetName" to risk.assetName,
                        "riskLevel" to risk.riskLevel,
                        "riskLevelText" to risk.riskLevelText,
                        "status" to risk.status,
                        "owner" to risk.owner,
                        "severity" to risk.severity,
                        "deadline" to risk.deadline
                    )
                }
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve risk assessment summary: ${e.message}")
        }
    }
}
