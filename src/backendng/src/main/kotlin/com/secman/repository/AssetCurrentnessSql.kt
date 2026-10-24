package com.secman.repository

/** Current inventory is independent of grants and destructive retention. */
object AssetCurrentnessSql {
    /**
     * Definition of observation evidence, mirrored by [com.secman.service.AssetCurrentnessPolicy].
     * It lives in exactly one place in the database: the generated `asset.observed_at` column
     * (V295). Queries filter on that column so `idx_asset_observed_at` can range-scan; this
     * expression is only the column's DDL.
     */
    const val OBSERVED_AT_EXPR = """CASE WHEN crowdstrike_managed = TRUE OR crowdstrike_last_imported_at IS NOT NULL
        OR crowdstrike_hostname IS NOT NULL OR crowdstrike_agent_seen_at IS NOT NULL
        OR owner = 'CrowdStrike Import'
        THEN crowdstrike_agent_seen_at ELSE verified_observed_at END"""
    const val OBSERVED_AT_DDL = "DATETIME(6) GENERATED ALWAYS AS (${AssetCurrentnessSql.OBSERVED_AT_EXPR}) VIRTUAL"
    const val OBSERVED_AT = "observed_at"
    const val CURRENT = "${AssetCurrentnessSql.OBSERVED_AT} BETWEEN UTC_TIMESTAMP(6) - INTERVAL 72 HOUR AND UTC_TIMESTAMP(6)"
    const val IDS = "SELECT id FROM asset WHERE ${AssetCurrentnessSql.CURRENT}"
    const val ASSETS = "(SELECT * FROM asset WHERE ${AssetCurrentnessSql.CURRENT})"
    const val UTC_NOW_JPQL = "CAST(SQL('UTC_TIMESTAMP(6)') AS LocalDateTime)"
    const val UTC_START_JPQL = "CAST(SQL('UTC_TIMESTAMP(6) - INTERVAL 72 HOUR') AS LocalDateTime)"
    // Same generated column as CURRENT, so the JPQL and native paths cannot diverge.
    const val JPQL = "(a.observedAt BETWEEN ${AssetCurrentnessSql.UTC_START_JPQL} AND ${AssetCurrentnessSql.UTC_NOW_JPQL})"
    const val CURRENT_SCAN_JPQL = "EXISTS (SELECT r.id FROM ScanResult r WHERE r.scan = s AND r.asset.id IN (SELECT a.id FROM Asset a WHERE ${AssetCurrentnessSql.JPQL})) AND NOT EXISTS (SELECT hidden.id FROM ScanResult hidden WHERE hidden.scan = s AND hidden.asset.id NOT IN (SELECT a.id FROM Asset a WHERE ${AssetCurrentnessSql.JPQL}))"
}

