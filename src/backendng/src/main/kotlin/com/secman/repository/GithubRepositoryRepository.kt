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
 * stable numeric [GithubRepository.githubRepoId] (rename-safe), with
 * [findByFullName] as a fallback match for pre-existing rows.
 */
@Repository
interface GithubRepositoryRepository : JpaRepository<GithubRepository, Long> {

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

    @Query("SELECT COALESCE(SUM(r.criticalCount), 0) FROM GithubRepository r")
    fun sumCriticalCount(): Long

    @Query("SELECT COALESCE(SUM(r.highCount), 0) FROM GithubRepository r")
    fun sumHighCount(): Long
}
