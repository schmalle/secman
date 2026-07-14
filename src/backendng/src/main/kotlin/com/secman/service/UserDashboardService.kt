package com.secman.service

import com.secman.domain.AssessmentBasisType
import com.secman.domain.RiskAssessment
import com.secman.dto.DashboardViewFlags
import com.secman.dto.OverdueAssetSummary
import com.secman.dto.RiskAssessmentTodoDto
import com.secman.dto.UserDashboardResponse
import com.secman.dto.VulnerabilitySeverityCounts
import com.secman.repository.AssessmentTokenRepository
import com.secman.repository.AssetRepository
import com.secman.repository.DemandRepository
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.WorkgroupRepository
import com.secman.repository.projection.SeverityDistributionRow
import io.micronaut.security.authentication.Authentication
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Aggregates the personal todo dashboard for regular users in one request.
 *
 * Query budget (the whole point of this service):
 * - 1x accessible-asset-ID resolution, shared via the request-scoped
 *   [AccessibleAssetIdsCache] so any other filter consumer in the same request reuses it
 * - 1x severity GROUP BY over vulnerability (skipped when the user has no assets)
 * - 1x single-row aggregate over the outdated-asset materialized view (skipped likewise)
 * - 6x indexed COUNT queries for the user's own exception requests
 * - 1x risk-assessment lookup by respondent + at most [MAX_RISK_ASSESSMENT_TODOS]
 *   small lookups for basis names and respond tokens
 * - 2x mapping/workgroup lookups for the quick-link flags
 *
 * ADMIN/SECCHAMPION callers take the unscoped query variants so no giant IN
 * clause is ever built (they normally see the statistics dashboard instead).
 */
@Singleton
open class UserDashboardService(
    private val userRepository: UserRepository,
    private val accessibleAssetIdsCache: AccessibleAssetIdsCache,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val outdatedAssetViewRepository: OutdatedAssetMaterializedViewRepository,
    private val exceptionRequestService: VulnerabilityExceptionRequestService,
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val assessmentTokenRepository: AssessmentTokenRepository,
    private val userMappingRepository: UserMappingRepository,
    private val workgroupRepository: WorkgroupRepository,
    private val assetRepository: AssetRepository,
    private val demandRepository: DemandRepository
) {

    companion object {
        const val MAX_RISK_ASSESSMENT_TODOS = 10

        /** Status values that mean "nothing left for the respondent to do" */
        private val CLOSED_STATUSES = setOf("COMPLETED", "CLOSED", "CANCELLED", "ARCHIVED")

        private val UNSCOPED_ROLES = setOf("ADMIN", "SECCHAMPION")
    }

    @Transactional(readOnly = true)
    open fun getDashboard(authentication: Authentication): UserDashboardResponse {
        val user = userRepository.findByUsername(authentication.name).orElseThrow {
            IllegalStateException("Authenticated user not found: ${authentication.name}")
        }
        val userId = requireNotNull(user.id) { "Persisted user must have an ID" }
        val email = user.email
        val unscoped = authentication.roles.any { it in UNSCOPED_ROLES }

        val accessibleAssetIds = accessibleAssetIdsCache.get(authentication)

        val vulnerabilities = when {
            unscoped -> toSeverityCounts(vulnerabilityRepository.findSeverityDistributionForAll())
            accessibleAssetIds.isEmpty() -> VulnerabilitySeverityCounts(0, 0, 0, 0, 0, 0)
            else -> toSeverityCounts(vulnerabilityRepository.findSeverityDistributionForAssets(accessibleAssetIds))
        }

        val overdue = buildOverdueSummary(unscoped, accessibleAssetIds)

        val exceptionRequests = exceptionRequestService.getUserRequestSummary(userId)

        val riskAssessments = riskAssessmentRepository.findByRespondentId(userId)
            .filter { (it.status.uppercase()) !in CLOSED_STATUSES }
            .sortedBy { it.endDate }
            .take(MAX_RISK_ASSESSMENT_TODOS)
            .map { toRiskAssessmentTodo(it, email) }

        val mappings = userMappingRepository.findByEmail(email)
        val views = DashboardViewFlags(
            accountVulns = mappings.any { !it.awsAccountId.isNullOrBlank() },
            workgroupVulns = workgroupRepository.findWorkgroupsByUserEmail(email).isNotEmpty(),
            domainVulns = mappings.any { !it.domain.isNullOrBlank() }
        )

        return UserDashboardResponse(
            assetCount = accessibleAssetIds.size,
            vulnerabilities = vulnerabilities,
            overdue = overdue,
            exceptionRequests = exceptionRequests,
            riskAssessments = riskAssessments,
            views = views
        )
    }

    private fun buildOverdueSummary(unscoped: Boolean, accessibleAssetIds: Set<Long>): OverdueAssetSummary {
        if (!unscoped && accessibleAssetIds.isEmpty()) {
            return OverdueAssetSummary(0, 0, 0, 0, null)
        }
        val row = if (unscoped) {
            outdatedAssetViewRepository.aggregateOverdueForAll()
        } else {
            outdatedAssetViewRepository.aggregateOverdueForAssets(accessibleAssetIds)
        }
        return OverdueAssetSummary(
            assetCount = row.assetCount ?: 0,
            criticalCount = row.criticalCount ?: 0,
            highCount = row.highCount ?: 0,
            oldestVulnDays = (row.oldestVulnDays ?: 0).toInt(),
            lastCalculatedAt = outdatedAssetViewRepository.findLatestCalculatedAt()
        )
    }

    private fun toSeverityCounts(rows: List<SeverityDistributionRow>): VulnerabilitySeverityCounts {
        var critical = 0L
        var high = 0L
        var medium = 0L
        var low = 0L
        var other = 0L
        rows.forEach { row ->
            val count = row.count?.toLong() ?: 0L
            when (row.severity?.trim()?.uppercase()) {
                "CRITICAL" -> critical += count
                "HIGH" -> high += count
                "MEDIUM" -> medium += count
                "LOW" -> low += count
                else -> other += count
            }
        }
        return VulnerabilitySeverityCounts(
            critical = critical,
            high = high,
            medium = medium,
            low = low,
            other = other,
            total = critical + high + medium + low + other
        )
    }

    private fun toRiskAssessmentTodo(assessment: RiskAssessment, email: String): RiskAssessmentTodoDto {
        val id = requireNotNull(assessment.id) { "Persisted risk assessment must have an ID" }
        val basisName = when (assessment.assessmentBasisType) {
            AssessmentBasisType.ASSET -> assetRepository.findById(assessment.assessmentBasisId)
                .map { it.name }.orElse(null)
            AssessmentBasisType.DEMAND -> demandRepository.findById(assessment.assessmentBasisId)
                .map { it.title }.orElse(null)
        }
        val now = LocalDateTime.now()
        val respondUrl = assessmentTokenRepository.findByRiskAssessmentIdAndEmail(id, email)
            .filter { !it.isUsed && it.expiresAt.isAfter(now) }
            .map { "/respond/${it.token}" }
            .orElse(null)
        val daysUntilDue = ChronoUnit.DAYS.between(LocalDate.now(), assessment.endDate)
        return RiskAssessmentTodoDto(
            id = id,
            basisType = assessment.assessmentBasisType.name,
            basisName = basisName,
            status = assessment.status,
            endDate = assessment.endDate,
            daysUntilDue = daysUntilDue,
            overdue = daysUntilDue < 0,
            respondUrl = respondUrl
        )
    }
}
