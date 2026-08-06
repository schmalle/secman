package com.secman.domain

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Materialized view entity for fast queries of outdated assets
 *
 * Represents pre-calculated denormalized view of assets with overdue vulnerabilities.
 * Refreshed asynchronously by MaterializedViewRefreshService.
 *
 * Feature: 034-outdated-assets
 * Task: T004
 * Spec reference: data-model.md
 */
@Entity
@Table(
    name = "outdated_asset_materialized_view",
    indexes = [
        Index(name = "idx_outdated_asset_id", columnList = "asset_id"),
        Index(name = "idx_outdated_asset_name", columnList = "asset_name"),
        Index(name = "idx_outdated_severity", columnList = "critical_count, high_count")
    ]
)
data class OutdatedAssetMaterializedView(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "asset_id", nullable = false)
    var assetId: Long,

    @Column(name = "asset_name", nullable = false, length = 255)
    var assetName: String,

    @Column(name = "asset_type", nullable = false, length = 50)
    var assetType: String,

    @Column(name = "total_overdue_count", nullable = false)
    var totalOverdueCount: Int,

    @Column(name = "critical_count", nullable = false)
    var criticalCount: Int = 0,

    @Column(name = "high_count", nullable = false)
    var highCount: Int = 0,

    @Column(name = "medium_count", nullable = false)
    var mediumCount: Int = 0,

    @Column(name = "low_count", nullable = false)
    var lowCount: Int = 0,

    @Column(name = "oldest_vuln_days", nullable = false)
    var oldestVulnDays: Int,

    @Column(name = "oldest_vuln_id", length = 50)
    var oldestVulnId: String? = null,

    @Column(name = "workgroup_ids", length = 500)
    var workgroupIds: String? = null,

    @Column(name = "ad_domain", length = 255)
    var adDomain: String? = null,

    @Column(name = "last_calculated_at", nullable = false)
    var lastCalculatedAt: LocalDateTime = LocalDateTime.now()
)
