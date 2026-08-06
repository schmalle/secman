package com.secman.repository

import com.secman.domain.GithubRepoFindingSnapshot
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.Optional

/**
 * Per-import finding-count history. The 30-day non-decrease alert uses the
 * newest snapshot at least `thresholdDays` old as its baseline.
 */
@Repository
interface GithubRepoFindingSnapshotRepository : JpaRepository<GithubRepoFindingSnapshot, Long> {

    /** Baseline for the non-decrease check: newest snapshot at or before [cutoff]. */
    fun findFirstByGithubRepositoryIdAndSnapshotAtLessThanEqualOrderBySnapshotAtDesc(
        githubRepositoryId: Long,
        cutoff: Instant
    ): Optional<GithubRepoFindingSnapshot>

}
