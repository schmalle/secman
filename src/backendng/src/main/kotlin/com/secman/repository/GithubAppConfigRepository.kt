package com.secman.repository

import com.secman.domain.GithubAppConfig
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface GithubAppConfigRepository : JpaRepository<GithubAppConfig, Long> {

    fun findByIsActive(isActive: Boolean): List<GithubAppConfig>

    @Query("SELECT c FROM GithubAppConfig c WHERE c.isActive = true")
    fun findActiveConfig(): Optional<GithubAppConfig>

    @Query("UPDATE GithubAppConfig c SET c.isActive = false WHERE c.isActive = true AND c.id != :excludeId")
    fun deactivateAllExcept(excludeId: Long): Int

    @Query("UPDATE GithubAppConfig c SET c.isActive = false WHERE c.isActive = true")
    fun deactivateAll(): Int
}
