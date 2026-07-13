package com.secman.repository

import com.secman.domain.GithubRepoAlertException
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface GithubRepoAlertExceptionRepository : JpaRepository<GithubRepoAlertException, Long> {

    fun findByGithubRepositoryId(githubRepositoryId: Long): List<GithubRepoAlertException>

    fun findByGithubRepositoryIdIn(githubRepositoryIds: List<Long>): List<GithubRepoAlertException>
}
