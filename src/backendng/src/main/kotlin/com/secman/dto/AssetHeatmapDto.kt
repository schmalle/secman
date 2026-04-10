package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class AssetHeatmapEntryDto(
    val assetId: Long,
    val assetName: String,
    val assetType: String,
    val criticalCount: Int,
    val highCount: Int,
    val mediumCount: Int,
    val lowCount: Int,
    val totalCount: Int,
    val heatLevel: String
)

@Serdeable
data class AssetHeatmapResponseDto(
    val entries: List<AssetHeatmapEntryDto>,
    val summary: AssetHeatmapSummaryDto,
    val lastCalculatedAt: String?
)

@Serdeable
data class AssetHeatmapSummaryDto(
    val totalAssets: Int,
    val redCount: Int,
    val yellowCount: Int,
    val greenCount: Int
)
