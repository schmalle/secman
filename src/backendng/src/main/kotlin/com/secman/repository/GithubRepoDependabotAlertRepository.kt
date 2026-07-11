package com.secman.repository

import com.secman.domain.GithubRepoDependabotAlert
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/**
 * Repository for per-repo Dependabot alert rows. [deleteByGithubRepositoryId]
 * is called before every reinsert in [com.secman.service.GithubRepoImportService.persistRepo]
 * (current-state replace, no history).
 */
@Repository
interface GithubRepoDependabotAlertRepository : JpaRepository<GithubRepoDependabotAlert, Long> {

    fun findByGithubRepositoryId(githubRepositoryId: Long): List<GithubRepoDependabotAlert>

    fun deleteByGithubRepositoryId(githubRepositoryId: Long): Long
}
