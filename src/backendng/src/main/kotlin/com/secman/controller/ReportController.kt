package com.secman.controller

import com.fasterxml.jackson.annotation.JsonInclude
import com.secman.repository.AssetRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.RiskRepository
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.annotation.Transactional
import java.time.LocalDate

@Controller("/api/reports")
@Secured("ADMIN", "RISK", "SECCHAMPION", "REPORT")
@ExecuteOn(TaskExecutors.BLOCKING)
open class ReportController(
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val riskRepository: RiskRepository,
    private val assetRepository: AssetRepository
) {

    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    data class AssessmentSummaryDto(
        val total: Long,
        val statusBreakdown: Map<String, Long>
    )

    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
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
        val status: String,
        val owner: String,
        val severity: String,
        val deadline: String?
    )

    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    data class ReportSummaryResponse(
        val assessmentSummary: AssessmentSummaryDto,
        val riskSummary: RiskSummaryDto,
        val assetCoverage: AssetCoverageDto,
        val recentAssessments: List<RecentAssessmentDto>,
        val highPriorityRisks: List<HighPriorityRiskDto>
    )

    @Serdeable
    data class MitigationSummaryDto(
        val totalOpenRisks: Long,
        val overdueRisks: Long,
        val unassignedRisks: Long
    )

    @Serdeable
    data class MitigationRiskDto(
        val id: Long,
        val name: String,
        val description: String,
        val assetName: String,
        val riskLevel: Int,
        val status: String,
        val owner: String,
        val severity: String,
        val deadline: String?,
        val isOverdue: Boolean,
        val likelihood: Int,
        val impact: Int
    )

    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    data class MitigationReportResponse(
        val summary: MitigationSummaryDto,
        val risks: List<MitigationRiskDto>
    )

    @Get("/risk-assessment-summary")
    @Transactional
    open fun getRiskAssessmentSummary(): ReportSummaryResponse {
        val allAssessments = riskAssessmentRepository.findAll()
        val allRisks = riskRepository.findAll()
        val totalAssets = assetRepository.count()

        // Assessment summary
        val assessmentStatusBreakdown = allAssessments
            .groupBy { it.status }
            .mapValues { it.value.size.toLong() }

        // Risk summary
        val riskStatusBreakdown = allRisks
            .groupBy { it.status }
            .mapValues { it.value.size.toLong() }

        val riskLevelBreakdown = allRisks
            .groupBy { (it.riskLevel ?: 0).toString() }
            .mapValues { it.value.size.toLong() }

        // Asset coverage - count distinct assets that have assessments
        val assetsWithAssessments = allAssessments
            .map { it.assessmentBasisId }
            .distinct()
            .count()
            .toLong()

        val coveragePercentage = if (totalAssets > 0) {
            (assetsWithAssessments.toDouble() / totalAssets.toDouble()) * 100.0
        } else {
            0.0
        }

        // Recent assessments (last 10, sorted by creation date desc)
        val recentAssessments = allAssessments
            .sortedByDescending { it.createdAt }
            .take(10)
            .map { ra ->
                RecentAssessmentDto(
                    id = ra.id ?: 0,
                    assetName = try { ra.getAssetName() } catch (_: Exception) { "Unknown" },
                    status = ra.status,
                    assessor = ra.assessor.username,
                    startDate = ra.startDate.toString(),
                    endDate = ra.endDate.toString()
                )
            }

        // High priority risks (level >= 3)
        val highPriorityRisks = riskRepository.findHighPriorityRisks(3)
            .map { risk ->
                HighPriorityRiskDto(
                    id = risk.id ?: 0,
                    name = risk.name,
                    assetName = risk.asset?.name ?: "Unknown",
                    riskLevel = risk.riskLevel ?: 0,
                    status = risk.status,
                    owner = risk.owner?.username ?: "Unassigned",
                    severity = risk.severity ?: "Unknown",
                    deadline = risk.deadline?.toString()
                )
            }

        return ReportSummaryResponse(
            assessmentSummary = AssessmentSummaryDto(
                total = allAssessments.size.toLong(),
                statusBreakdown = assessmentStatusBreakdown
            ),
            riskSummary = RiskSummaryDto(
                total = allRisks.size.toLong(),
                statusBreakdown = riskStatusBreakdown,
                riskLevelBreakdown = riskLevelBreakdown
            ),
            assetCoverage = AssetCoverageDto(
                totalAssets = totalAssets,
                assetsWithAssessments = assetsWithAssessments,
                coveragePercentage = Math.round(coveragePercentage * 100.0) / 100.0
            ),
            recentAssessments = recentAssessments,
            highPriorityRisks = highPriorityRisks
        )
    }

    @Get("/risk-mitigation-status")
    @Transactional
    open fun getRiskMitigationStatus(): MitigationReportResponse {
        val allRisks = riskRepository.findAll()
        val today = LocalDate.now()

        val openRisks = allRisks.filter { it.status != "CLOSED" && it.status != "MITIGATED" }
        val overdueRisks = openRisks.filter { risk ->
            risk.deadline != null && risk.deadline!! <= today
        }
        val unassignedRisks = openRisks.filter { it.owner == null }

        val riskDtos = openRisks.map { risk ->
            val isOverdue = risk.deadline != null && risk.deadline!! <= today
            MitigationRiskDto(
                id = risk.id ?: 0,
                name = risk.name,
                description = risk.description ?: "",
                assetName = risk.asset?.name ?: "Unknown",
                riskLevel = risk.riskLevel ?: 0,
                status = risk.status,
                owner = risk.owner?.username ?: "Unassigned",
                severity = risk.severity ?: "Unknown",
                deadline = risk.deadline?.toString(),
                isOverdue = isOverdue,
                likelihood = risk.likelihood ?: 1,
                impact = risk.impact ?: 1
            )
        }

        return MitigationReportResponse(
            summary = MitigationSummaryDto(
                totalOpenRisks = openRisks.size.toLong(),
                overdueRisks = overdueRisks.size.toLong(),
                unassignedRisks = unassignedRisks.size.toLong()
            ),
            risks = riskDtos
        )
    }
}
