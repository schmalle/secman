package com.secman.domain

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Pre-calculated heatmap data for each asset.
 *
 * Stores severity counts per asset so the heatmap UI can render instantly
 * without running expensive aggregate queries at request time.
 * Refreshed during CrowdStrike import (materialized view refresh lifecycle).
 *
 * Heat level rules:
 * - RED: asset has any CRITICAL vulnerability, or more than 100 HIGH vulnerabilities
 * - YELLOW: asset has HIGH vulnerabilities (1-100)
 * - GREEN: no CRITICAL or HIGH vulnerabilities
 */
@Entity
@Table(
    name = "asset_heatmap_entry",
    indexes = [
        Index(name = "idx_heatmap_asset_id", columnList = "asset_id", unique = true),
        Index(name = "idx_heatmap_heat_level", columnList = "heat_level"),
        Index(name = "idx_heatmap_asset_name", columnList = "asset_name")
    ]
)
data class AssetHeatmapEntry(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "asset_id", nullable = false, unique = true)
    var assetId: Long,

    @Column(name = "asset_name", nullable = false, length = 255)
    var assetName: String,

    @Column(name = "asset_type", nullable = false, length = 50)
    var assetType: String,

    @Column(name = "critical_count", nullable = false)
    var criticalCount: Int = 0,

    @Column(name = "high_count", nullable = false)
    var highCount: Int = 0,

    @Column(name = "medium_count", nullable = false)
    var mediumCount: Int = 0,

    @Column(name = "low_count", nullable = false)
    var lowCount: Int = 0,

    @Column(name = "total_count", nullable = false)
    var totalCount: Int = 0,

    @Column(name = "heat_level", nullable = false, length = 10)
    var heatLevel: String = "GREEN",

    @Column(name = "cloud_account_id", length = 255)
    var cloudAccountId: String? = null,

    @Column(name = "ad_domain", length = 255)
    var adDomain: String? = null,

    @Column(name = "owner", length = 255)
    var owner: String? = null,

    @Column(name = "workgroup_ids", length = 500)
    var workgroupIds: String? = null,

    @Column(name = "manual_creator_id")
    var manualCreatorId: Long? = null,

    @Column(name = "scan_uploader_id")
    var scanUploaderId: Long? = null,

    @Column(name = "last_calculated_at", nullable = false)
    var lastCalculatedAt: LocalDateTime = LocalDateTime.now()
) {
    companion object {
        const val HEAT_RED = "RED"
        const val HEAT_YELLOW = "YELLOW"
        const val HEAT_GREEN = "GREEN"

        fun calculateHeatLevel(criticalCount: Int, highCount: Int): String = when {
            criticalCount > 0 -> HEAT_RED
            highCount > 100 -> HEAT_RED
            highCount > 0 -> HEAT_YELLOW
            else -> HEAT_GREEN
        }
    }
}
