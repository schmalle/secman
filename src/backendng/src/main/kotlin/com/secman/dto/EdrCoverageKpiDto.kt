package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

/**
 * API response for the "EC2 instances with a CrowdStrike sensor" KPI.
 *
 * `available = false` (all other fields null) means the number would not yet be a
 * measurement: either no calculation has completed, or no CrowdStrike import has run since
 * the agent-seen signal was introduced. Reporting 0% in that state would read as a
 * fleet-wide EDR outage rather than as missing data.
 *
 * Both [totalEc2Instances] and [eligibleEc2Instances] are exposed on purpose. The
 * percentage is computed over the *eligible* population (total minus assets with an
 * approved "No EDR possible" exception), and surfacing the raw total alongside it keeps
 * the effect of those exceptions visible instead of hidden inside the number.
 */
@Serdeable
data class EdrCoverageKpiResponse(
    val available: Boolean,
    val percentage: Double? = null,
    /** All EC2 instances known to secman, before EDR exemptions. */
    val totalEc2Instances: Long? = null,
    /** The KPI denominator: [totalEc2Instances] minus [excludedByNoEdrException]. */
    val eligibleEc2Instances: Long? = null,
    /** The KPI numerator: eligible instances seen by CrowdStrike within the freshness window. */
    val coveredEc2Instances: Long? = null,
    /** EC2 instances removed from the denominator by an active "No EDR possible" exception. */
    val excludedByNoEdrException: Long? = null,
    /** The freshness window, so the UI can state it rather than implying "right now". */
    val agentSeenWithinDays: Long? = null,
    val lastCalculatedAt: LocalDateTime? = null
)

/**
 * Internal shape persisted as JSON in `vulnerability_statistics_cache`
 * (cache_key = EdrCoverageKpiService.CACHE_KEY).
 */
@Serdeable
data class EdrCoverageKpiCacheData(
    val totalEc2Instances: Long,
    val eligibleEc2Instances: Long,
    val coveredEc2Instances: Long,
    val excludedByNoEdrException: Long,
    val percentage: Double,
    val agentSeenWithinDays: Long
)
