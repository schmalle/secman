package com.secman.repository

/**
 * Native SQL for the "accounts with the longest-open findings" report.
 *
 * Age anchor is COALESCE(first_seen_at, scan_timestamp): first_seen_at is the SLA
 * anchor preserved across re-imports but is nullable on rows written before V-era
 * backfills, and ranking on the raw column would silently drop the oldest accounts
 * this report exists to surface.
 *
 * `excepted = 0` (the precomputed column, not a correlated EXCEPTION_MATCH) keeps
 * the grouped scan sargable on idx_vulnerability_excepted_sort.
 */
object AccountAgeSql {

    private const val AGE_ANCHOR = "COALESCE(v.first_seen_at, v.scan_timestamp)"

    /** Step 1 — rank accounts. `:limit` is interpolated by Micronaut Data as a bind parameter. */
    const val RANK_ACCOUNTS = """
        SELECT a.cloud_account_id                               AS awsAccountId,
               MIN(COALESCE(v.first_seen_at, v.scan_timestamp)) AS oldestFirstSeen,
               COUNT(*)                                         AS openFindingCount,
               COUNT(DISTINCT a.id)                             AS affectedAssetCount
        FROM vulnerability v
        JOIN asset a ON v.asset_id = a.id
        WHERE v.excepted = 0
          AND a.cloud_account_id IS NOT NULL
          AND a.cloud_account_id <> ''
        GROUP BY a.cloud_account_id
        ORDER BY oldestFirstSeen ASC
        LIMIT :limit
        """

    /**
     * Step 2 (batched) — the single oldest finding for EVERY account in `:accountIds` in
     * one query, scoped by an IN list of at most MAX_LIMIT (50) account ids per the design.
     *
     * A window function partitions the IN-scoped rows by account and orders each partition
     * by the same age anchor + tie-break as step 1 (oldest COALESCE(first_seen_at,
     * scan_timestamp) first, ties broken by vulnerability_id ASC); the outer query keeps
     * only rn = 1. This is one scan of the scoped rows, not a correlated subquery re-run
     * per account — the partitioning happens once, in a single pass.
     */
    const val OLDEST_FINDING_DETAIL = """
        SELECT awsAccountId, cve, severity, assetName, assetInstanceId
        FROM (
            SELECT a.cloud_account_id  AS awsAccountId,
                   v.vulnerability_id  AS cve,
                   v.cvss_severity     AS severity,
                   a.name              AS assetName,
                   a.cloud_instance_id AS assetInstanceId,
                   ROW_NUMBER() OVER (
                       PARTITION BY a.cloud_account_id
                       ORDER BY COALESCE(v.first_seen_at, v.scan_timestamp) ASC, v.vulnerability_id ASC
                   ) AS rn
            FROM vulnerability v
            JOIN asset a ON v.asset_id = a.id
            WHERE v.excepted = 0
              AND a.cloud_account_id IN (:accountIds)
        ) ranked
        WHERE rn = 1
        """
}
