package com.secman.repository

import com.secman.domain.AssetHeatmapEntry
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

/**
 * Repository for AssetHeatmapEntry pre-calculated heatmap data.
 *
 * Provides queries for heatmap retrieval with access control filtering.
 */
@Repository
interface AssetHeatmapRepository : JpaRepository<AssetHeatmapEntry, Long> {

    @Query("SELECT h FROM AssetHeatmapEntry h WHERE h.assetId IN (:assetIds) ORDER BY h.assetName ASC")
    fun findByAssetIds(assetIds: Collection<Long>): List<AssetHeatmapEntry>

    @Query("SELECT MAX(h.lastCalculatedAt) FROM AssetHeatmapEntry h")
    fun findLatestCalculatedAt(): LocalDateTime?

    override fun deleteAll()
}
