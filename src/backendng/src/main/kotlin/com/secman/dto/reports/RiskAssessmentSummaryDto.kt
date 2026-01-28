package com.secman.dto.reports

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class RiskAssessmentSummaryDto(
    val assessmentSummary: AssessmentSummaryDto,
    val riskSummary: RiskSummaryDto,
    val assetCoverage: AssetCoverageDto,
    val recentAssessments: List<RecentAssessmentDto>,
    val highPriorityRisks: List<HighPriorityRiskDto>
)

@Serdeable
data class AssessmentSummaryDto(
    val total: Long,
    val statusBreakdown: Map<String, Long>
)

@Serdeable
data class RiskSummaryDto(
    val total: Long,
    val statusBreakdown: Map<String, Long>,
    val riskLevelBreakdown: Map<String, Long>
)

@Serdeable
data class AssetCoverageDto(
    val totalAssets: Long,
    val assetsWithAssessments: Long,
    val coveragePercentage: Double
)

@Serdeable
data class RecentAssessmentDto(
    val id: Long,
    val assetName: String,
    val status: String,
    val assessor: String,
    val startDate: String,
    val endDate: String
)

@Serdeable
data class HighPriorityRiskDto(
    val id: Long,
    val name: String,
    val assetName: String,
    val riskLevel: Int,
    val riskLevelText: String,
    val status: String,
    val owner: String?,
    val severity: String?,
    val deadline: String?
)
