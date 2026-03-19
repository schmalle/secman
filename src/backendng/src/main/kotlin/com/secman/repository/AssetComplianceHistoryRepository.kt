package com.secman.repository

import com.secman.domain.AssetComplianceHistory
import com.secman.domain.ComplianceStatus
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import java.time.LocalDateTime

/**
 * Repository for AssetComplianceHistory entity.
 * Feature: ec2-vulnerability-tracking
 */
@Repository
interface AssetComplianceHistoryRepository : JpaRepository<AssetComplianceHistory, Long> {

    /**
     * Get the most recent compliance status for an asset (for change detection during import).
     */
    @Query("SELECT h FROM AssetComplianceHistory h WHERE h.assetId = :assetId ORDER BY h.changedAt DESC")
    fun findLatestByAssetId(assetId: Long, pageable: Pageable): List<AssetComplianceHistory>

    /**
     * Get full compliance history for a single asset (for detail timeline page).
     */
    fun findByAssetIdOrderByChangedAtDesc(assetId: Long, pageable: Pageable): Page<AssetComplianceHistory>

    /**
     * Get all history entries for a single asset (unpaginated, for timeline).
     */
    fun findByAssetIdOrderByChangedAtDesc(assetId: Long): List<AssetComplianceHistory>

    /**
     * Count assets by current compliance status.
     * Uses a subquery to get the latest status per asset.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT h.assetId) FROM AssetComplianceHistory h
        WHERE h.status = :status
        AND h.changedAt = (
            SELECT MAX(h2.changedAt) FROM AssetComplianceHistory h2 WHERE h2.assetId = h.assetId
        )
        """
    )
    fun countByLatestStatus(status: ComplianceStatus): Long

    /**
     * Count distinct assets that have any compliance history.
     */
    @Query("SELECT COUNT(DISTINCT h.assetId) FROM AssetComplianceHistory h")
    fun countDistinctAssets(): Long

    /**
     * Get all asset IDs that already have compliance history (for bulk recalculation).
     */
    @Query("SELECT DISTINCT h.assetId FROM AssetComplianceHistory h")
    fun findDistinctAssetIds(): List<Long>

    /**
     * Paginated overview of latest compliance status per asset, joined with asset table.
     * Returns raw results to be mapped in service layer.
     */
    @Query(
        value = """
            SELECT h.id, h.asset_id, h.status, h.changed_at, h.overdue_count, h.oldest_vuln_days, h.source,
                   a.name AS asset_name, a.cloud_instance_id, a.type AS asset_type
            FROM asset_compliance_history h
            INNER JOIN asset a ON h.asset_id = a.id
            WHERE h.changed_at = (
                SELECT MAX(h2.changed_at) FROM asset_compliance_history h2 WHERE h2.asset_id = h.asset_id
            )
            AND (:searchTerm IS NULL OR LOWER(a.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
            AND (:statusFilter IS NULL OR h.status = :statusFilter)
            ORDER BY h.changed_at DESC
            LIMIT :pageSize OFFSET :pageOffset
        """,
        nativeQuery = true
    )
    fun findLatestStatusOverview(
        searchTerm: String?,
        statusFilter: String?,
        pageSize: Int,
        pageOffset: Int
    ): List<Any>

    /**
     * Count for the paginated overview query.
     */
    @Query(
        value = """
            SELECT COUNT(*) FROM asset_compliance_history h
            INNER JOIN asset a ON h.asset_id = a.id
            WHERE h.changed_at = (
                SELECT MAX(h2.changed_at) FROM asset_compliance_history h2 WHERE h2.asset_id = h.asset_id
            )
            AND (:searchTerm IS NULL OR LOWER(a.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
            AND (:statusFilter IS NULL OR h.status = :statusFilter)
        """,
        nativeQuery = true
    )
    fun countLatestStatusOverview(searchTerm: String?, statusFilter: String?): Long
}
