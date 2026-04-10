package com.secman.repository

import com.secman.domain.AssetHeatmapEntry
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime
import java.util.Optional

/**
 * Repository for AssetHeatmapEntry pre-calculated heatmap data.
 *
 * Provides queries for heatmap retrieval with access control filtering.
 */
@Repository
interface AssetHeatmapRepository : JpaRepository<AssetHeatmapEntry, Long> {

    /**
     * Find heatmap entries for assets accessible to a non-admin user.
     * Mirrors the unified access control query from AssetRepository.
     */
    @Query(
        value = """
            SELECT h.* FROM asset_heatmap_entry h
            WHERE
                h.asset_id IN (
                    SELECT aw.asset_id FROM asset_workgroups aw
                    JOIN user_workgroups uw ON aw.workgroup_id = uw.workgroup_id
                    WHERE uw.user_id = :userId
                )
                OR h.manual_creator_id = :userId
                OR h.scan_uploader_id = :userId
                OR h.cloud_account_id IN (
                    SELECT um.aws_account_id FROM user_mapping um
                    WHERE um.email = :userEmail AND um.aws_account_id IS NOT NULL
                )
                OR LOWER(h.ad_domain) IN (
                    SELECT LOWER(um.domain) FROM user_mapping um
                    WHERE um.email = :userEmail AND um.domain IS NOT NULL
                )
                OR h.cloud_account_id IN (
                    SELECT DISTINCT um2.aws_account_id
                    FROM aws_account_sharing acs
                    JOIN users u_source ON u_source.id = acs.source_user_id
                    JOIN user_mapping um2 ON um2.email = u_source.email AND um2.aws_account_id IS NOT NULL
                    WHERE acs.target_user_id = :userId
                )
                OR h.owner = :username
            ORDER BY h.asset_name ASC
        """,
        nativeQuery = true
    )
    fun findAccessibleEntries(userId: Long, userEmail: String, username: String): List<AssetHeatmapEntry>

    @Query("SELECT MAX(h.lastCalculatedAt) FROM AssetHeatmapEntry h")
    fun findLatestCalculatedAt(): LocalDateTime?

    override fun deleteAll()
}
