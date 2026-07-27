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
     * Step 2 — the single oldest finding in one account.
     *
     * Takes the already-known minimum timestamp as an equality predicate so this is a
     * selective filter rather than a filesort over the whole account. Ties are broken
     * by vulnerability_id so repeated runs report the same row.
     */
    const val OLDEST_FINDING_DETAIL = """
        SELECT a.cloud_account_id  AS awsAccountId,
               v.vulnerability_id  AS cve,
               v.cvss_severity     AS severity,
               a.name              AS assetName,
               a.cloud_instance_id AS assetInstanceId
        FROM vulnerability v
        JOIN asset a ON v.asset_id = a.id
        WHERE v.excepted = 0
          AND a.cloud_account_id = :accountId
          AND COALESCE(v.first_seen_at, v.scan_timestamp) = :firstSeenAt
        ORDER BY v.vulnerability_id ASC
        LIMIT 1
        """
}
