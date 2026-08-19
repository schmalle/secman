package com.secman.repository

import com.secman.domain.OutdatedAssetMaterializedView
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import java.time.LocalDateTime
import java.util.Optional

/**
 * Repository for OutdatedAssetMaterializedView entity
 *
 * Provides efficient queries for outdated assets with workgroup filtering,
 * search, severity filtering, and sorting capabilities.
 *
 * Feature: 034-outdated-assets
 * Task: T007
 * Spec reference: data-model.md, contracts/01-get-outdated-assets.md
 */
@Repository
interface OutdatedAssetMaterializedViewRepository : JpaRepository<OutdatedAssetMaterializedView, Long> {

    /**
     * Rebuild the entire view in ONE statement, entirely inside the database.
     *
     * This replaced a read-all-then-aggregate-in-Kotlin rebuild: load every overdue
     * `Vulnerability` as a managed entity with its `Asset` fetch-joined, `groupBy` it into maps,
     * and build one entity per asset. At production scale that was ~166k managed entities plus
     * their dirty-checking snapshots plus three derived collections, all live at once — the
     * largest single contributor to the 2026-07-30 import OutOfMemoryError. Peak JVM heap here is
     * now a single Int (the affected-row count), independent of table size.
     *
     * The derived table computes the per-asset aggregates AND picks the oldest row in ONE pass:
     * window aggregates give the counts, `ROW_NUMBER()` ordered by the SLA anchor gives the
     * argmin, so `rn = 1` yields exactly one output row per asset carrying both.
     *
     * Semantics preserved from the Kotlin it replaces (`createMaterializedRecordFromVulns`):
     *  - SLA anchor is `COALESCE(first_seen_at, scan_timestamp)`, NOT scan_timestamp alone,
     *    which is refreshed on every re-import and would understate true age.
     *  - Severity comparison is case-insensitive: severity is stored title-case ("Critical") and
     *    utf8mb4_general_ci (V205) makes `=` case-insensitive, matching the previous explicit
     *    `equals(..., ignoreCase = true)`.
     *  - `TIMESTAMPDIFF(DAY, anchor, now)` truncates toward zero exactly like
     *    `ChronoUnit.DAYS.between`.
     *  - Only assets with at least one overdue, non-excepted vulnerability produce a row —
     *    `PARTITION BY` cannot create a partition with no rows.
     *  - `excepted = 0` is pushed into SQL, replacing the in-memory post-filter.
     *  - `product_class <> 'INSTALLER_ARTIFACT'` matches every other overdue surface, so an
     *    installer payload never makes an asset look outdated.
     *
     * Two deliberate behaviour changes, both improvements:
     *  - Ties for "oldest" now resolve to the lexicographically smallest `vulnerability_id`
     *    instead of whatever order the result set happened to arrive in.
     *  - `workgroup_ids` is ordered ascending. Safe: every reader matches it with
     *    `LIKE CONCAT('%', :workgroupId, '%')`, which is order-independent, and it is a
     *    perf hint rather than the auth boundary (see OutdatedAssetService).
     *
     * MUST be called inside the same transaction as the preceding `deleteAll()` — see
     * MaterializedViewRefreshService.swapMaterializedView for why readers must never observe a
     * half-swapped view.
     *
     * @param thresholdDate vulnerabilities first seen before this instant count as overdue
     * @param now single timestamp used for both the age arithmetic and `last_calculated_at`
     * @return number of rows inserted, i.e. assets now in the view
     */
    @Query(
        value = """
        INSERT INTO outdated_asset_materialized_view
            (asset_id, asset_name, asset_type, total_overdue_count,
             critical_count, high_count, medium_count, low_count,
             oldest_vuln_days, oldest_vuln_id, workgroup_ids, ad_domain, last_calculated_at)
        SELECT
            r.asset_id,
            a.name,
            a.type,
            r.total_overdue_count,
            r.critical_count,
            r.high_count,
            r.medium_count,
            r.low_count,
            TIMESTAMPDIFF(DAY, r.oldest_anchor, :now),
            r.oldest_vuln_id,
            COALESCE((SELECT GROUP_CONCAT(aw.workgroup_id ORDER BY aw.workgroup_id)
                      FROM asset_workgroups aw WHERE aw.asset_id = r.asset_id), ''),
            a.ad_domain,
            :now
        FROM (
            SELECT
                v.asset_id,
                v.vulnerability_id AS oldest_vuln_id,
                COALESCE(v.first_seen_at, v.scan_timestamp) AS oldest_anchor,
                COUNT(*) OVER (PARTITION BY v.asset_id) AS total_overdue_count,
                SUM(CASE WHEN v.cvss_severity = 'CRITICAL' THEN 1 ELSE 0 END)
                    OVER (PARTITION BY v.asset_id) AS critical_count,
                SUM(CASE WHEN v.cvss_severity = 'HIGH' THEN 1 ELSE 0 END)
                    OVER (PARTITION BY v.asset_id) AS high_count,
                SUM(CASE WHEN v.cvss_severity = 'MEDIUM' THEN 1 ELSE 0 END)
                    OVER (PARTITION BY v.asset_id) AS medium_count,
                SUM(CASE WHEN v.cvss_severity = 'LOW' THEN 1 ELSE 0 END)
                    OVER (PARTITION BY v.asset_id) AS low_count,
                ROW_NUMBER() OVER (
                    PARTITION BY v.asset_id
                    ORDER BY COALESCE(v.first_seen_at, v.scan_timestamp) ASC,
                             v.vulnerability_id ASC
                ) AS rn
            FROM vulnerability v
            WHERE v.excepted = 0
              AND v.product_class <> 'INSTALLER_ARTIFACT'
              AND COALESCE(v.first_seen_at, v.scan_timestamp) < :thresholdDate
        ) r
        JOIN asset a ON a.id = r.asset_id
        WHERE r.rn = 1
        """,
        nativeQuery = true
    )
    fun rebuildFromOverdueVulnerabilities(thresholdDate: LocalDateTime, now: LocalDateTime): Int

    /**
     * Count the assets a rebuild would produce, without building it. Used only to give the
     * refresh job (and its SSE progress stream) a meaningful `totalAssets` denominator before
     * the single-statement rebuild runs.
     *
     * Must use the SAME predicates as [rebuildFromOverdueVulnerabilities] or the progress
     * denominator will not match the eventual row count.
     */
    @Query(
        value = """
        SELECT COUNT(DISTINCT v.asset_id)
        FROM vulnerability v
        WHERE v.excepted = 0
          AND v.product_class <> 'INSTALLER_ARTIFACT'
          AND COALESCE(v.first_seen_at, v.scan_timestamp) < :thresholdDate
        """,
        nativeQuery = true
    )
    fun countAssetsWithOverdueVulnerabilities(thresholdDate: LocalDateTime): Long

    /**
     * Find outdated assets with optional filtering
     *
     * Supports:
     * - Workgroup filtering (for VULN users)
     * - Search by asset name (case-insensitive)
     * - Minimum severity filtering (CRITICAL, HIGH, MEDIUM, LOW)
     * - Pagination and sorting
     *
     * Task: T007, T079 (filter queries)
     * Spec reference: FR-011, FR-012, FR-013
     */
    @Query(
        value = """
            SELECT v FROM OutdatedAssetMaterializedView v
            WHERE (:workgroupId IS NULL
                OR :workgroupId = ''
                OR v.workgroupIds IS NULL
                OR v.workgroupIds LIKE CONCAT('%', :workgroupId, '%'))
            AND (:searchTerm IS NULL OR LOWER(v.assetName) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
            AND (:minSeverity IS NULL OR
                (CASE
                    WHEN :minSeverity = 'CRITICAL' THEN v.criticalCount > 0
                    WHEN :minSeverity = 'HIGH' THEN (v.criticalCount > 0 OR v.highCount > 0)
                    WHEN :minSeverity = 'MEDIUM' THEN (v.criticalCount > 0 OR v.highCount > 0 OR v.mediumCount > 0)
                    ELSE true
                END))
            AND (:adDomain IS NULL OR LOWER(v.adDomain) = LOWER(:adDomain))
        """,
        countQuery = """
            SELECT COUNT(v) FROM OutdatedAssetMaterializedView v
            WHERE (:workgroupId IS NULL
                OR :workgroupId = ''
                OR v.workgroupIds IS NULL
                OR v.workgroupIds LIKE CONCAT('%', :workgroupId, '%'))
            AND (:searchTerm IS NULL OR LOWER(v.assetName) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
            AND (:minSeverity IS NULL OR
                (CASE
                    WHEN :minSeverity = 'CRITICAL' THEN v.criticalCount > 0
                    WHEN :minSeverity = 'HIGH' THEN (v.criticalCount > 0 OR v.highCount > 0)
                    WHEN :minSeverity = 'MEDIUM' THEN (v.criticalCount > 0 OR v.highCount > 0 OR v.mediumCount > 0)
                    ELSE true
                END))
            AND (:adDomain IS NULL OR LOWER(v.adDomain) = LOWER(:adDomain))
        """
    )
    fun findOutdatedAssets(
        workgroupId: String?,
        searchTerm: String?,
        minSeverity: String?,
        adDomain: String?,
        pageable: Pageable
    ): Page<OutdatedAssetMaterializedView>

    /**
     * Get latest refresh timestamp for staleness indicator
     *
     * Task: T007
     * Spec reference: FR-017
     */
    @Query("SELECT MAX(v.lastCalculatedAt) FROM OutdatedAssetMaterializedView v")
    fun getLastRefreshTimestamp(): Optional<LocalDateTime>

    /**
     * Find latest calculated timestamp (nullable for service)
     *
     * Task: T016
     */
    @Query("SELECT MAX(v.lastCalculatedAt) FROM OutdatedAssetMaterializedView v")
    fun findLatestCalculatedAt(): LocalDateTime?

    /**
     * Count outdated assets with workgroup filtering
     *
     * Task: T016
     */
    @Query("""
        SELECT COUNT(v) FROM OutdatedAssetMaterializedView v
        WHERE (:workgroupId IS NULL
            OR :workgroupId = ''
            OR v.workgroupIds IS NULL
            OR v.workgroupIds LIKE CONCAT('%', :workgroupId, '%'))
    """)
    fun countOutdatedAssets(workgroupId: String?): Long

    /**
     * Delete all rows (used during refresh to clear old data)
     *
     * Task: T007
     * Spec reference: data-model.md (refresh process)
     */
    override fun deleteAll()

    /**
     * Single-row aggregate over the view for a set of accessible asset IDs.
     *
     * Powers the user todo dashboard's "overdue patching" card with one query
     * instead of streaming view rows and counting in memory. The caller passes
     * IDs from AssetFilterService, so the ID filter IS the auth boundary here.
     *
     * CAST AS SIGNED keeps the projection Long-typed (MariaDB SUM(INT) is DECIMAL).
     */
    @Query(
        value = """
            SELECT CAST(COUNT(*) AS SIGNED) AS assetCount,
                   CAST(COALESCE(SUM(v.critical_count), 0) AS SIGNED) AS criticalCount,
                   CAST(COALESCE(SUM(v.high_count), 0) AS SIGNED) AS highCount,
                   CAST(COALESCE(MAX(v.oldest_vuln_days), 0) AS SIGNED) AS oldestVulnDays
            FROM outdated_asset_materialized_view v
            WHERE v.asset_id IN :assetIds
        """,
        nativeQuery = true
    )
    fun aggregateOverdueForAssets(assetIds: Set<Long>): com.secman.repository.projection.OverdueAssetAggregateRow

    /**
     * Unscoped variant of [aggregateOverdueForAssets] for ADMIN/SECCHAMPION callers,
     * avoiding an IN clause over the full asset-ID universe.
     */
    @Query(
        value = """
            SELECT CAST(COUNT(*) AS SIGNED) AS assetCount,
                   CAST(COALESCE(SUM(v.critical_count), 0) AS SIGNED) AS criticalCount,
                   CAST(COALESCE(SUM(v.high_count), 0) AS SIGNED) AS highCount,
                   CAST(COALESCE(MAX(v.oldest_vuln_days), 0) AS SIGNED) AS oldestVulnDays
            FROM outdated_asset_materialized_view v
        """,
        nativeQuery = true
    )
    fun aggregateOverdueForAll(): com.secman.repository.projection.OverdueAssetAggregateRow
}
