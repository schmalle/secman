package com.secman.repository

import com.secman.domain.MaterializedViewRefreshJob
import com.secman.domain.RefreshJobStatus
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Repository for MaterializedViewRefreshJob entity
 *
 * Provides queries for job tracking and concurrent refresh prevention.
 *
 * Feature: 034-outdated-assets
 * Task: T008
 * Spec reference: data-model.md
 */
@Repository
interface MaterializedViewRefreshJobRepository : JpaRepository<MaterializedViewRefreshJob, Long> {

    /**
     * Find currently running refresh job
     *
     * Used for concurrent refresh prevention (return 409 Conflict if exists).
     *
     * Task: T008
     * Spec reference: FR-018, contracts/02-trigger-manual-refresh.md
     */
    @Query("SELECT j FROM MaterializedViewRefreshJob j WHERE j.status = 'RUNNING' ORDER BY j.startedAt DESC")
    fun findRunningJob(): Optional<MaterializedViewRefreshJob>

    /**
     * Count jobs by status (for metrics)
     *
     * Task: T008
     * Spec reference: FR-021 (observability metrics)
     */
    fun countByStatus(status: RefreshJobStatus): Long
}
