package com.secman.service

import com.secman.dto.reports.*
import com.secman.repository.AssetRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.RiskRepository
import jakarta.inject.Singleton
import java.time.LocalDate

/**
 * Service for generating report data for MCP Lense Reports
 *
 * Responsibilities:
 * - Provide aggregated risk assessment summary data
 * - Provide risk mitigation status tracking data
 * - Apply access control filtering based on accessible asset IDs
 *
 * Feature: 069-mcp-lense-reports
 * Task: T007
 * User Stories: US1 (Risk Assessment Summary), US2 (Risk Mitigation Status)
 * Spec reference: spec.md FR-001, FR-002
 *
 * Access Control: Uses pre-computed accessible asset IDs from McpExecutionContext
 * - accessibleAssetIds = null → No filtering (ADMIN or non-delegated API key)
 * - accessibleAssetIds = Set<Long> → Filter to only those assets
 * - accessibleAssetIds = empty set → Return empty results
 */
@Singleton
open class ReportService(
    private val riskRepository: RiskRepository,
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val assetRepository: AssetRepository
) {

    companion object {
        private const val RECENT_ASSESSMENTS_LIMIT = 10
        private const val HIGH_PRIORITY_RISK_MIN_LEVEL = 3 // High and Critical (levels 3-4)
    }

    /**
     * Get risk assessment summary report data
     *
     * Returns aggregated statistics including:
     * - Assessment summary: total count and status breakdown
     * - Risk summary: total count, status breakdown, and risk level breakdown
     * - Asset coverage: total assets, assets with assessments, coverage percentage
     * - Recent assessments: 10 most recent assessments
     * - High priority risks: risks with level >= 3 (High, Critical)
     *
     * @param accessibleAssetIds Set of asset IDs the user can access, null for no filtering
     * @return RiskAssessmentSummaryDto with all aggregated data
     */
    open fun getRiskAssessmentSummary(accessibleAssetIds: Set<Long>?): RiskAssessmentSummaryDto {
        // Handle empty access set - return empty results
        if (accessibleAssetIds != null && accessibleAssetIds.isEmpty()) {
            return createEmptyRiskAssessmentSummary()
        }

        // Get assessment summary
        val assessmentSummary = getAssessmentSummary(accessibleAssetIds)

        // Get risk summary
        val riskSummary = getRiskSummary(accessibleAssetIds)

        // Get asset coverage
        val assetCoverage = getAssetCoverage(accessibleAssetIds)

        // Get recent assessments (10 most recent)
        val recentAssessments = getRecentAssessments(accessibleAssetIds)

        // Get high priority risks (level >= 3)
        val highPriorityRisks = getHighPriorityRisks(accessibleAssetIds)

        return RiskAssessmentSummaryDto(
            assessmentSummary = assessmentSummary,
            riskSummary = riskSummary,
            assetCoverage = assetCoverage,
            recentAssessments = recentAssessments,
            highPriorityRisks = highPriorityRisks
        )
    }

    /**
     * Get risk mitigation status report data
     *
     * Returns summary statistics and detailed risk list:
     * - Summary: total open risks, overdue risks, unassigned risks
     * - Detailed risk list with mitigation status, overdue flag, likelihood/impact scores
     *
     * @param accessibleAssetIds Set of asset IDs the user can access, null for no filtering
     * @param statusFilter Optional status filter (OPEN, MITIGATED, CLOSED), null for all statuses
     * @return RiskMitigationStatusDto with summary and risk list
     */
    open fun getRiskMitigationStatus(
        accessibleAssetIds: Set<Long>?,
        statusFilter: String? = null
    ): RiskMitigationStatusDto {
        // Handle empty access set - return empty results
        if (accessibleAssetIds != null && accessibleAssetIds.isEmpty()) {
            return createEmptyRiskMitigationStatus()
        }

        val today = LocalDate.now()

        // Get all risks (with optional status filter and access control)
        val risks = if (accessibleAssetIds == null) {
            // No filtering - get all risks
            if (statusFilter != null) {
                riskRepository.findByStatus(statusFilter)
            } else {
                riskRepository.findAll()
            }
        } else {
            // Filter by accessible assets
            if (statusFilter != null) {
                riskRepository.findByAssetIdInAndStatus(accessibleAssetIds, statusFilter)
            } else {
                riskRepository.findByAssetIdIn(accessibleAssetIds)
            }
        }

        // Calculate summary statistics
        val openRisks = risks.filter { it.status == "OPEN" }
        val overdueRisks = risks.filter { risk ->
            risk.deadline != null && risk.deadline!! < today && risk.status != "CLOSED"
        }
        val unassignedRisks = risks.filter { it.owner == null && it.status == "OPEN" }

        val summary = MitigationSummaryDto(
            totalOpenRisks = openRisks.size.toLong(),
            overdueRisks = overdueRisks.size.toLong(),
            unassignedRisks = unassignedRisks.size.toLong()
        )

        // Map risks to detailed DTOs
        val riskDetails = risks.map { risk ->
            val isOverdue = risk.deadline != null && risk.deadline!! < today && risk.status != "CLOSED"

            RiskMitigationDetailDto(
                id = risk.id ?: 0L,
                name = risk.name,
                description = risk.description,
                assetName = risk.asset?.name ?: "Unknown",
                riskLevel = risk.riskLevel ?: 1,
                riskLevelText = risk.getRiskLevelText(),
                status = risk.status,
                owner = risk.owner?.username,
                severity = risk.severity,
                deadline = risk.deadline?.toString(),
                isOverdue = isOverdue,
                likelihood = risk.likelihood ?: 1,
                impact = risk.impact ?: 1
            )
        }

        return RiskMitigationStatusDto(
            summary = summary,
            risks = riskDetails
        )
    }

    private fun getAssessmentSummary(accessibleAssetIds: Set<Long>?): AssessmentSummaryDto {
        val assessments = if (accessibleAssetIds == null) {
            riskAssessmentRepository.findAll()
        } else {
            riskAssessmentRepository.findByAssetBasisIdIn(accessibleAssetIds)
        }

        val statusBreakdown = assessments
            .groupBy { it.status }
            .mapValues { it.value.size.toLong() }

        return AssessmentSummaryDto(
            total = assessments.size.toLong(),
            statusBreakdown = statusBreakdown
        )
    }

    private fun getRiskSummary(accessibleAssetIds: Set<Long>?): RiskSummaryDto {
        val risks = if (accessibleAssetIds == null) {
            riskRepository.findAll()
        } else {
            riskRepository.findByAssetIdIn(accessibleAssetIds)
        }

        val statusBreakdown = risks
            .groupBy { it.status }
            .mapValues { it.value.size.toLong() }

        val riskLevelBreakdown = risks
            .groupBy { it.getRiskLevelText() }
            .mapValues { it.value.size.toLong() }

        return RiskSummaryDto(
            total = risks.size.toLong(),
            statusBreakdown = statusBreakdown,
            riskLevelBreakdown = riskLevelBreakdown
        )
    }

    private fun getAssetCoverage(accessibleAssetIds: Set<Long>?): AssetCoverageDto {
        val totalAssets = if (accessibleAssetIds == null) {
            assetRepository.count()
        } else {
            accessibleAssetIds.size.toLong()
        }

        val assetsWithAssessments = if (accessibleAssetIds == null) {
            riskAssessmentRepository.countDistinctAssetsWithAssessments()
        } else {
            // Count assessments that are for assets in the accessible set
            val assessments = riskAssessmentRepository.findByAssetBasisIdIn(accessibleAssetIds)
            assessments.map { it.assessmentBasisId }.distinct().count().toLong()
        }

        val coveragePercentage = if (totalAssets > 0) {
            (assetsWithAssessments.toDouble() / totalAssets * 100).let {
                kotlin.math.round(it * 100) / 100 // Round to 2 decimal places
            }
        } else {
            0.0
        }

        return AssetCoverageDto(
            totalAssets = totalAssets,
            assetsWithAssessments = assetsWithAssessments,
            coveragePercentage = coveragePercentage
        )
    }

    private fun getRecentAssessments(accessibleAssetIds: Set<Long>?): List<RecentAssessmentDto> {
        val assessments = if (accessibleAssetIds == null) {
            riskAssessmentRepository.findRecentAssessments()
        } else {
            riskAssessmentRepository.findRecentAssessmentsByAssetIds(accessibleAssetIds)
        }

        return assessments
            .take(RECENT_ASSESSMENTS_LIMIT)
            .map { assessment ->
                RecentAssessmentDto(
                    id = assessment.id ?: 0L,
                    assetName = assessment.getAssetName(),
                    status = assessment.status,
                    assessor = assessment.assessor.username,
                    startDate = assessment.startDate.toString(),
                    endDate = assessment.endDate.toString()
                )
            }
    }

    private fun getHighPriorityRisks(accessibleAssetIds: Set<Long>?): List<HighPriorityRiskDto> {
        val risks = if (accessibleAssetIds == null) {
            riskRepository.findHighPriorityRisks(HIGH_PRIORITY_RISK_MIN_LEVEL)
        } else {
            riskRepository.findHighPriorityRisksByAssetIds(accessibleAssetIds, HIGH_PRIORITY_RISK_MIN_LEVEL)
        }

        return risks.map { risk ->
            HighPriorityRiskDto(
                id = risk.id ?: 0L,
                name = risk.name,
                assetName = risk.asset?.name ?: "Unknown",
                riskLevel = risk.riskLevel ?: 1,
                riskLevelText = risk.getRiskLevelText(),
                status = risk.status,
                owner = risk.owner?.username,
                severity = risk.severity,
                deadline = risk.deadline?.toString()
            )
        }
    }

    private fun createEmptyRiskAssessmentSummary(): RiskAssessmentSummaryDto {
        return RiskAssessmentSummaryDto(
            assessmentSummary = AssessmentSummaryDto(total = 0, statusBreakdown = emptyMap()),
            riskSummary = RiskSummaryDto(total = 0, statusBreakdown = emptyMap(), riskLevelBreakdown = emptyMap()),
            assetCoverage = AssetCoverageDto(totalAssets = 0, assetsWithAssessments = 0, coveragePercentage = 0.0),
            recentAssessments = emptyList(),
            highPriorityRisks = emptyList()
        )
    }

    private fun createEmptyRiskMitigationStatus(): RiskMitigationStatusDto {
        return RiskMitigationStatusDto(
            summary = MitigationSummaryDto(totalOpenRisks = 0, overdueRisks = 0, unassignedRisks = 0),
            risks = emptyList()
        )
    }
}
