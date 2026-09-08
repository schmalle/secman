package com.secman.repository

import com.secman.domain.GithubRepository
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import java.util.Optional

/**
 * Repository for imported GitHub repositories. The import upserts by the
 * instance plus numeric [GithubRepository.githubRepoId] (rename-safe).
 * A reused name must never replace a different repository's identity.
 */
@Repository
interface GithubRepositoryRepository : JpaRepository<GithubRepository, Long> {
    fun findByGithubInstanceAndGithubRepoId(githubInstance: String, githubRepoId: Long): Optional<GithubRepository>

    fun findByGithubRepoId(githubRepoId: Long): Optional<GithubRepository>

    fun findByFullName(fullName: String): Optional<GithubRepository>

    fun findByFullNameContainingIgnoreCaseOrOwnerContainingIgnoreCaseOrOwnerEmailContainingIgnoreCase(
        fullName: String,
        owner: String,
        ownerEmail: String,
        pageable: Pageable
    ): Page<GithubRepository>

    fun findByOwnerIgnoreCase(owner: String): List<GithubRepository>

    fun countByOwnerIgnoreCase(owner: String): Long

    /** Repos with no notification email set yet — candidates for owner-email auto-discovery. */
    fun findByOwnerEmailIsNull(): List<GithubRepository>

    @Query("SELECT COALESCE(SUM(r.criticalCount), 0) FROM GithubRepository r")
    fun sumCriticalCount(): Long

    @Query("SELECT COALESCE(SUM(r.highCount), 0) FROM GithubRepository r")
    fun sumHighCount(): Long
}
