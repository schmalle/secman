package com.secman.repository.projection

import io.micronaut.core.annotation.Introspected
import io.micronaut.serde.annotation.Serdeable

/**
 * Single-row aggregate over the outdated-asset materialized view, scoped to a
 * set of accessible asset IDs. Every column is CAST AS SIGNED in the native
 * query so the values arrive as Long regardless of MariaDB's SUM/COUNT
 * return-type rules (SUM of INT is DECIMAL).
 *
 * Used by the user todo dashboard (GET /api/user-dashboard).
 */
@Serdeable
@Introspected
data class OverdueAssetAggregateRow(
    val assetCount: Long?,
    val criticalCount: Long?,
    val highCount: Long?,
    val oldestVulnDays: Long?
)
