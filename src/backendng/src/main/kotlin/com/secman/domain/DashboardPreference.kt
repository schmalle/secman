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
