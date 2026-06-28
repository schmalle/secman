package com.secman.repository

import com.secman.domain.DependabotAlert
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Repository for ingested GitHub Dependabot alerts.
 *
 * The import path upserts on the natural key `(repository, alertNumber)` via
 * [findByRepositoryAndAlertNumber]; the UI reads the full set ordered for display.
 */
@Repository
interface DependabotAlertRepository : JpaRepository<DependabotAlert, Long> {

    fun findByRepositoryAndAlertNumber(repository: String, alertNumber: Int): Optional<DependabotAlert>

    fun findByRepository(repository: String): List<DependabotAlert>

    fun countByState(state: String): Long
}
