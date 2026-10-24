package com.secman.service

import com.secman.domain.AssetHeatmapEntry
import com.secman.repository.AssetHeatmapRepository
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Service for computing and querying pre-calculated asset heatmap data.
 *
 * The heatmap is refreshed during the materialized view refresh lifecycle,
 * triggered after each CrowdStrike import. The data captures per-asset
 * vulnerability severity counts so the UI can render a heatmap instantly.
 */
@Singleton
open class AssetHeatmapService(
    private val assetHeatmapRepository: AssetHeatmapRepository,
    private val entityManager: EntityManager
) {
    private val log = LoggerFactory.getLogger(AssetHeatmapService::class.java)

    /**
     * Recalculate the entire heatmap table.
     * Called during materialized view refresh after CrowdStrike import,
     * on startup when the table is empty, or via manual refresh endpoint.
     *
     * One DELETE and one INSERT ... SELECT in a single transaction: the aggregate, the heat level
     * and the denormalized workgroup ids are all computed by MariaDB. The previous version read
     * every aggregate row into the JVM and wrote it back through saveAll(), which cannot batch
     * IDENTITY inserts, so it cost one INSERT round trip per asset. Readers keep seeing the
     * previous rows until commit (READ COMMITTED), exactly as before.
     *
     * @return number of heatmap entries created
     */
    @Transactional
    open fun recalculateHeatmap(): Int {
        val startTime = System.currentTimeMillis()
        log.info("Starting heatmap recalculation")

        entityManager.createNativeQuery("DELETE FROM asset_heatmap_entry").executeUpdate()
        val created = entityManager.createNativeQuery("""
            INSERT INTO asset_heatmap_entry (
                asset_id, asset_name, asset_type, critical_count, high_count, medium_count, low_count,
                total_count, heat_level, cloud_account_id, ad_domain, owner, workgroup_ids,
                manual_creator_id, scan_uploader_id, last_calculated_at
            )
            SELECT
                c.asset_id, c.asset_name, c.asset_type, c.critical_count, c.high_count, c.medium_count,
                c.low_count, c.total_count,
                CASE
                    WHEN c.critical_count > 0 THEN '${AssetHeatmapEntry.HEAT_RED}'
                    WHEN c.high_count > 100 THEN '${AssetHeatmapEntry.HEAT_RED}'
                    WHEN c.high_count > 0 THEN '${AssetHeatmapEntry.HEAT_YELLOW}'
                    ELSE '${AssetHeatmapEntry.HEAT_GREEN}'
                END,
                c.cloud_account_id, c.ad_domain, c.owner, wg.wg_ids,
                c.manual_creator_id, c.scan_uploader_id, :now
            FROM (
                SELECT
                    a.id AS asset_id,
                    a.name AS asset_name,
                    a.type AS asset_type,
                    COALESCE(SUM(CASE WHEN v.cvss_severity = 'CRITICAL' THEN 1 ELSE 0 END), 0) AS critical_count,
                    COALESCE(SUM(CASE WHEN v.cvss_severity = 'HIGH' THEN 1 ELSE 0 END), 0) AS high_count,
                    COALESCE(SUM(CASE WHEN v.cvss_severity = 'MEDIUM' THEN 1 ELSE 0 END), 0) AS medium_count,
                    COALESCE(SUM(CASE WHEN v.cvss_severity = 'LOW' THEN 1 ELSE 0 END), 0) AS low_count,
                    COUNT(v.id) AS total_count,
                    a.cloud_account_id,
                    a.ad_domain,
                    a.owner,
                    a.manual_creator_id,
                    a.scan_uploader_id
                FROM ${com.secman.repository.AssetCurrentnessSql.ASSETS} a
                LEFT JOIN ${com.secman.repository.CrowdStrikeSnapshotSql.CURRENT_FINDINGS} v ON v.asset_id = a.id
                GROUP BY a.id, a.name, a.type, a.cloud_account_id, a.ad_domain, a.owner, a.manual_creator_id, a.scan_uploader_id
            ) c
            LEFT JOIN (
                SELECT aw.asset_id, GROUP_CONCAT(aw.workgroup_id ORDER BY aw.workgroup_id) AS wg_ids
                FROM asset_workgroups aw
                JOIN workgroup w ON w.id = aw.workgroup_id
                WHERE w.enabled = TRUE
                GROUP BY aw.asset_id
            ) wg ON wg.asset_id = c.asset_id
        """).setParameter("now", LocalDateTime.now()).executeUpdate()

        val durationMs = System.currentTimeMillis() - startTime
        log.info("Heatmap recalculation completed: {} entries in {}ms", created, durationMs)
        return created
    }

    fun getLastCalculatedAt(): LocalDateTime? {
        return assetHeatmapRepository.findLatestCalculatedAt()
    }

    fun isEmpty(): Boolean {
        return assetHeatmapRepository.count() == 0L
    }
}
