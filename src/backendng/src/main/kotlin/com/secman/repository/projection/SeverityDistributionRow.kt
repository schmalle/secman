package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable
import java.math.BigInteger

/**
 * Row representation for native severity distribution queries.
 *
 * We rely on Micronaut Data to materialize the native query into this DTO,
 * avoiding runtime Map/TypeConverter issues while keeping conversion logic type-safe.
 *
 * Feature: 036-vuln-stats-lense
 */
@Serdeable
@Introspected
data class SeverityDistributionRow(
    val severity: String?,
    val count: BigInteger?
)

/**
 * Fold grouped severity rows into the flat DTO. A List extension rather than a member
 * function because N rows collapse into one DTO. Shared by the live path
 * (VulnerabilityStatisticsService) and the cache refresh (VulnerabilityStatisticsCacheService)
 * so the two cannot drift — see [MostCommonVulnerabilityRow.toDto].
 *
 * Two behaviours worth knowing before you "fix" them:
 *
 *  1. `uppercase()` before bucketing is REQUIRED. Severity is stored title-case ("Critical")
 *     by the CrowdStrike importer and by addVulnerabilityFromCli — an exact match against
 *     "CRITICAL" silently yields zero for every imported row.
 *  2. Severities outside these five buckets (CrowdStrike also emits "Informational") are
 *     deliberately DROPPED, not folded into `unknown`. They then count toward neither the
 *     total nor any percentage. This preserves long-standing behaviour of both callers;
 *     changing it would move every percentage on the dashboard and is a product decision.
 */
fun List<SeverityDistributionRow>.toSeverityDistributionDto(): com.secman.dto.SeverityDistributionDto {
    var critical = 0L
    var high = 0L
    var medium = 0L
    var low = 0L
    var unknown = 0L

    // Accumulate rather than assign. Today `GROUP BY COALESCE(cvss_severity, 'UNKNOWN')` under
    // utf8mb4_general_ci (V205) already collapses case variants into one row per bucket, so this
    // is equivalent — but it stays correct if that collation ever changes.
    forEach { row ->
        val count = row.count?.toLong() ?: 0L
        when ((row.severity ?: "UNKNOWN").uppercase()) {
            "CRITICAL" -> critical += count
            "HIGH" -> high += count
            "MEDIUM" -> medium += count
            "LOW" -> low += count
            "UNKNOWN" -> unknown += count
        }
    }

    return com.secman.dto.SeverityDistributionDto(
        critical = critical,
        high = high,
        medium = medium,
        low = low,
        unknown = unknown
    )
}
