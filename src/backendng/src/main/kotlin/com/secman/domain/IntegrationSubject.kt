package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "integration_subject", uniqueConstraints = [
    UniqueConstraint(name = "uk_integration_subject_asset", columnNames = ["scanner_id", "asset_id"]),
    UniqueConstraint(name = "uk_integration_subject_repo", columnNames = ["scanner_id", "github_repository_id"])
])
class IntegrationSubject(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "scanner_id", nullable = false) var scannerId: Long = 0,
    @Column(name = "asset_id", nullable = false) var assetId: Long = 0,
    @Column(name = "github_repository_id") var githubRepositoryId: Long? = null,
    @Column(name = "last_status", length = 20) var lastStatus: String? = null,
    @Column(name = "last_scan_at") var lastScanAt: Instant? = null,
    @Column(name = "last_successful_scan_at") var lastSuccessfulScanAt: Instant? = null,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
)
