package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

/**
 * API response for the "AWS servers with no vulnerability older than 30 days" KPI.
 *
 * `available = false` (all other fields null) means no calculation has ever
 * completed yet (e.g. fresh install, before the first CrowdStrike import).
 */
@Serdeable
data class AwsCleanServerKpiResponse(
    val available: Boolean,
    val percentage: Double? = null,
    val totalAwsServers: Long? = null,
    val cleanAwsServers: Long? = null,
    val lastCalculatedAt: LocalDateTime? = null
)

/**
 * Internal shape persisted as JSON in `vulnerability_statistics_cache`
 * (cache_key = AwsCleanServerKpiService.CACHE_KEY).
 */
@Serdeable
data class AwsCleanServerKpiCacheData(
    val totalAwsServers: Long,
    val cleanAwsServers: Long,
    val percentage: Double
)
