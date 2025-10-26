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
        """
    )
    fun findOutdatedAssets(
        workgroupId: String?,
        searchTerm: String?,
        minSeverity: String?,
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
}
