package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * Point-in-time open Dependabot alert counts for a GitHub repository.
 * One row is written per repository per import run; the 30-day
 * non-decrease alert compares the current counts against the newest
 * snapshot that is at least `thresholdDays` old.
 */
@Entity
@Table(
    name = "github_repo_finding_snapshot",
    indexes = [
        Index(name = "idx_ghsnap_repo_at", columnList = "github_repository_id, snapshot_at")
    ]
)
@Serdeable
data class GithubRepoFindingSnapshot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "github_repository_id", nullable = false)
    var githubRepositoryId: Long = 0,

    @Column(name = "snapshot_at", nullable = false)
    var snapshotAt: Instant = Instant.now(),

    @Column(name = "critical_count", nullable = false)
    var criticalCount: Int = 0,

    @Column(name = "high_count", nullable = false)
    var highCount: Int = 0
)
