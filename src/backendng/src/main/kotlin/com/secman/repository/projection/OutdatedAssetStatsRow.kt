package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable
import java.math.BigInteger

/**
 * Row representation for native outdated asset statistics queries.
 *
 * Used by MaterializedViewRefreshService for batch loading vulnerability statistics
 * instead of N+1 queries per asset.
 *
 * Feature: Outdated Assets Performance Optimization (Fix 3)
 */
@Serdeable
@Introspected
data class OutdatedAssetStatsRow(
    val assetId: BigInteger,
    val assetName: String?,
    val assetType: String?,
    val totalOverdueCount: BigInteger,
    val criticalCount: BigInteger,
    val highCount: BigInteger,
    val mediumCount: BigInteger,
    val lowCount: BigInteger,
    val oldestVulnDays: BigInteger?,
    val oldestVulnId: String?
)
