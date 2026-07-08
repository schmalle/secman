package com.secman.repository

import com.secman.domain.GithubRepository
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
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

    fun listOrderByFullName(): List<GithubRepository>
}
