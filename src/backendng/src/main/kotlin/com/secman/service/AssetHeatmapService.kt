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

    companion object {
        private const val BATCH_SIZE = 1000
    }

    /**
     * Recalculate the entire heatmap table.
     * Called during materialized view refresh after CrowdStrike import,
     * on startup when the table is empty, or via manual refresh endpoint.
     *
     * Uses a single aggregate SQL query to compute severity counts per asset,
     * then writes the results in batches.
     *
     * @return number of heatmap entries created
     */
    @Transactional
    open fun recalculateHeatmap(): Int {
        val startTime = System.currentTimeMillis()
        log.info("Starting heatmap recalculation")

        // Step 1: Clear old data
        clearHeatmap()

        // Step 2: Aggregate vulnerability counts per asset using native SQL
        @Suppress("UNCHECKED_CAST")
        val rows = entityManager.createNativeQuery("""
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
            FROM asset a
            LEFT JOIN vulnerability v ON v.asset_id = a.id
            GROUP BY a.id, a.name, a.type, a.cloud_account_id, a.ad_domain, a.owner, a.manual_creator_id, a.scan_uploader_id
        """).resultList as List<Array<Any?>>

        log.info("Aggregated vulnerability counts for {} assets", rows.size)

        // Step 3: Build heatmap entries and also denormalize workgroup IDs
        val workgroupMap = loadWorkgroupMap()
        val now = LocalDateTime.now()

        val entries = rows.map { row ->
            val assetId = (row[0] as Number).toLong()
            val criticalCount = (row[3] as Number).toInt()
            val highCount = (row[4] as Number).toInt()

            AssetHeatmapEntry(
                assetId = assetId,
                assetName = row[1] as String,
                assetType = row[2] as String,
                criticalCount = criticalCount,
                highCount = highCount,
                mediumCount = (row[5] as Number).toInt(),
                lowCount = (row[6] as Number).toInt(),
                totalCount = (row[7] as Number).toInt(),
                heatLevel = AssetHeatmapEntry.calculateHeatLevel(criticalCount, highCount),
                cloudAccountId = row[8] as? String,
                adDomain = row[9] as? String,
                owner = row[10] as? String,
                manualCreatorId = (row[11] as? Number)?.toLong(),
                scanUploaderId = (row[12] as? Number)?.toLong(),
                workgroupIds = workgroupMap[assetId],
                lastCalculatedAt = now
            )
        }

        // Step 4: Save in batches
        entries.chunked(BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            saveHeatmapBatch(batch)
            log.debug("Saved heatmap batch {}: {} entries", batchIndex + 1, batch.size)
        }

        val durationMs = System.currentTimeMillis() - startTime
        log.info("Heatmap recalculation completed: {} entries in {}ms", entries.size, durationMs)
        return entries.size
    }

    @Transactional
    open fun clearHeatmap() {
        assetHeatmapRepository.deleteAll()
    }

    @Transactional
    open fun saveHeatmapBatch(entries: List<AssetHeatmapEntry>) {
        assetHeatmapRepository.saveAll(entries)
    }

    /**
     * Load workgroup IDs per asset as a denormalized comma-separated string.
     */
    private fun loadWorkgroupMap(): Map<Long, String?> {
        @Suppress("UNCHECKED_CAST")
        val rows = entityManager.createNativeQuery("""
            SELECT asset_id, GROUP_CONCAT(workgroup_id ORDER BY workgroup_id) AS wg_ids
            FROM asset_workgroups
            GROUP BY asset_id
        """).resultList as List<Array<Any?>>

        return rows.associate { row ->
            (row[0] as Number).toLong() to (row[1] as? String)
        }
    }

    fun getLastCalculatedAt(): LocalDateTime? {
        return assetHeatmapRepository.findLatestCalculatedAt()
    }

    fun isEmpty(): Boolean {
        return assetHeatmapRepository.count() == 0L
    }
}
