package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * Stores per-user visibility preferences for home dashboard security KPI cards
 */
@Entity
@Table(name = "dashboard_preference")
data class DashboardPreference(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false, unique = true)
    val userId: Long,

    @Column(name = "show_aws_clean_server_kpi", nullable = false)
    val showAwsCleanServerKpi: Boolean = true,

    @Column(name = "show_edr_coverage_kpi", nullable = false)
    val showEdrCoverageKpi: Boolean = true,

    @Column(name = "show_account_finding_age", nullable = false)
    val showAccountFindingAge: Boolean = true,

    @Column(name = "show_asset_inventory", nullable = false)
    val showAssetInventory: Boolean = true,

    @Column(name = "show_users", nullable = false)
    val showUsers: Boolean = true,

    @Column(name = "show_active_users", nullable = false)
    val showActiveUsers: Boolean = true,

    @Column(name = "show_active_releases", nullable = false)
    val showActiveReleases: Boolean = true,

    @Column(name = "show_running_risk_assessments", nullable = false)
    val showRunningRiskAssessments: Boolean = true,

    @Column(name = "show_last_crowdstrike_import", nullable = false)
    val showLastCrowdStrikeImport: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}
