package com.secman.repository

import com.secman.service.AssetCurrentnessPolicy

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
    const val WINDOW_INTERVAL = "INTERVAL ${AssetCurrentnessPolicy.WINDOW_HOURS} HOUR"
    const val CURRENT = "${AssetCurrentnessSql.OBSERVED_AT} BETWEEN UTC_TIMESTAMP(6) - $WINDOW_INTERVAL AND UTC_TIMESTAMP(6)"
    // CrowdStrike also supplies UUID instance IDs for other cloud providers.
    const val EC2_INSTANCE = "cloud_instance_id REGEXP '^i-([0-9a-fA-F]{8}|[0-9a-fA-F]{17})$'"
    const val EC2_ASSETS = "(SELECT * FROM asset WHERE $CURRENT AND $EC2_INSTANCE)"
    const val IDS = "SELECT id FROM asset WHERE ${AssetCurrentnessSql.CURRENT}"
    const val ASSETS = "(SELECT * FROM asset WHERE ${AssetCurrentnessSql.CURRENT})"
    const val UTC_NOW_JPQL = "CAST(SQL('UTC_TIMESTAMP(6)') AS LocalDateTime)"
    const val UTC_START_JPQL = "CAST(SQL('UTC_TIMESTAMP(6) - $WINDOW_INTERVAL') AS LocalDateTime)"
    // Same generated column as CURRENT, so the JPQL and native paths cannot diverge.
    const val JPQL = "(a.observedAt BETWEEN ${AssetCurrentnessSql.UTC_START_JPQL} AND ${AssetCurrentnessSql.UTC_NOW_JPQL})"
    const val CURRENT_SCAN_JPQL = "EXISTS (SELECT r.id FROM ScanResult r WHERE r.scan = s AND r.asset.id IN (SELECT a.id FROM Asset a WHERE ${AssetCurrentnessSql.JPQL})) AND NOT EXISTS (SELECT hidden.id FROM ScanResult hidden WHERE hidden.scan = s AND hidden.asset.id NOT IN (SELECT a.id FROM Asset a WHERE ${AssetCurrentnessSql.JPQL}))"
}

