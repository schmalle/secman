package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Aggregated payload for the personal todo dashboard shown to users without
 * the ADMIN or SECCHAMPION role (GET /api/user-dashboard).
 *
 * Designed as a single round-trip: the frontend renders the whole dashboard
 * from one response instead of fanning out to several role-gated endpoints
 * (most of which regular users cannot call anyway).
 */
@Serdeable
data class UserDashboardResponse(
    /** Number of assets the user can access via the unified 10-point filter */
    val assetCount: Int,

    /** Open (non-excepted) vulnerability counts on the user's accessible assets */
    val vulnerabilities: VulnerabilitySeverityCounts,

    /** Assets with vulnerabilities past their remediation SLA, from the materialized view */
    val overdue: OverdueAssetSummary,

    /** The user's own exception requests, by status */
    val exceptionRequests: ExceptionRequestSummaryDto,

    /** Open risk assessments where the user is the respondent, soonest deadline first */
    val riskAssessments: List<RiskAssessmentTodoDto>,

    /** Which per-user vulnerability views have data for this user (drives quick links) */
    val views: DashboardViewFlags
)

@Serdeable
data class VulnerabilitySeverityCounts(
    val critical: Long,
    val high: Long,
    val medium: Long,
    val low: Long,
    /** Informational / unknown / anything not mapped to the four canonical bands */
    val other: Long,
    val total: Long
)

@Serdeable
data class OverdueAssetSummary(
    /** Accessible assets that have at least one overdue vulnerability */
    val assetCount: Long,
    val criticalCount: Long,
    val highCount: Long,
    /** Age in days of the oldest overdue vulnerability across those assets */
    val oldestVulnDays: Int,
    /** When the materialized view was last refreshed (null = never calculated) */
    val lastCalculatedAt: LocalDateTime?
)

@Serdeable
data class RiskAssessmentTodoDto(
    val id: Long,
    /** DEMAND or ASSET */
    val basisType: String,
    /** Demand title or asset name; null when the basis record no longer exists */
    val basisName: String?,
    val status: String,
    val endDate: LocalDate,
    /** Negative when past the deadline */
    val daysUntilDue: Long,
    val overdue: Boolean,
    /** Link to the token-based respond page when a valid unused token exists */
    val respondUrl: String?
)

@Serdeable
data class DashboardViewFlags(
    val accountVulns: Boolean,
    val workgroupVulns: Boolean,
    val domainVulns: Boolean
)
