package com.secman.repository

import com.secman.domain.DashboardPreference
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Repository for DashboardPreference entity
 */
@Repository
interface DashboardPreferenceRepository : JpaRepository<DashboardPreference, Long> {
    /**
     * Find dashboard KPI visibility preference by user ID
     */
    fun findByUserId(userId: Long): Optional<DashboardPreference>
}
