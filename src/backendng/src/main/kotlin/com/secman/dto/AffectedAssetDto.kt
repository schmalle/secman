package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * DTO for an asset affected by a specific vulnerability (CVE)
 *
 * Used in the CVE drilldown feature to show which systems are affected
 * within the user's accessible scope and selected domain filter.
 */
@Serdeable
data class AffectedAssetDto(
    val assetId: Long,
    val assetName: String,
    val assetIp: String?,
    val adDomain: String?,
    val assetType: String?
)

/**
 * Response DTO for affected assets by CVE query
 *
 * Includes the CVE ID and list of affected assets for display in the modal.
 */
@Serdeable
data class AffectedAssetsByCveDto(
    val cveId: String,
    val severity: String,
    val affectedAssets: List<AffectedAssetDto>,
    val totalCount: Int
)

/**
 * DTO for an asset that has a specific product installed
 *
 * Used in the product drilldown feature to show which systems
 * have a specific vulnerable product installed.
 */
@Serdeable
data class AssetWithProductDto(
    val assetId: Long,
    val assetName: String,
    val assetIp: String?,
    val adDomain: String?,
    val assetType: String?,
    val vulnerabilityCount: Long
)

/**
 * Response DTO for assets by product query
 *
 * Includes the product name and list of assets where it's installed.
 */
@Serdeable
data class AssetsByProductDto(
    val product: String,
    val assets: List<AssetWithProductDto>,
    val totalCount: Int
)
