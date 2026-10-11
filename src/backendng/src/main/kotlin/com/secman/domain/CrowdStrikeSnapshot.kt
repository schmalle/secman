package com.secman.domain

import jakarta.persistence.*
import java.time.LocalDateTime

/** One successfully fetched severity scope, including snapshots with no finding rows. */
@Entity
@Table(name = "crowdstrike_snapshot", indexes = [Index(name = "idx_cs_snapshot_scope", columnList = "asset_id,severity,active")])
data class CrowdStrikeSnapshot(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "asset_id", nullable = false)
    var assetId: Long,
    @Column(name = "crowdstrike_aid", nullable = false, length = 64)
    var crowdStrikeAid: String,
    @Column(nullable = false, length = 20)
    var severity: String,
    @Column(name = "observed_at", nullable = false)
    var observedAt: LocalDateTime,
    @Column(name = "completed_at", nullable = false)
    var completedAt: LocalDateTime = LocalDateTime.ofInstant(java.time.Instant.now(), java.time.ZoneOffset.UTC),
    @Column(nullable = false)
    var complete: Boolean = true,
    @Column(nullable = false)
    var active: Boolean = true,
    /** Request-wide counts, repeated for each severity covered by this observation. */
    @Column(name = "fetched_count")
    var fetchedCount: Int? = null,
    @Column(name = "accepted_count")
    var acceptedCount: Int? = null,
    @Column(name = "missing_cve_count")
    var missingCveCount: Int? = null,
    @Column(name = "duplicate_count")
    var duplicateCount: Int? = null,
    @Column(name = "coverage_kind", length = 32)
    var coverageKind: String? = null
)
