package com.secman.dto.reports

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class RiskMitigationStatusDto(
    val summary: MitigationSummaryDto,
    val risks: List<RiskMitigationDetailDto>
)

@Serdeable
data class MitigationSummaryDto(
    val totalOpenRisks: Long,
    val overdueRisks: Long,
    val unassignedRisks: Long
)

@Serdeable
data class RiskMitigationDetailDto(
    val id: Long,
    val name: String,
    val description: String?,
    val assetName: String,
    val riskLevel: Int,
    val riskLevelText: String,
    val status: String,
    val owner: String?,
    val severity: String?,
    val deadline: String?,
    val isOverdue: Boolean,
    val likelihood: Int,
    val impact: Int
)
