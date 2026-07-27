package com.secman.repository

import com.secman.domain.CrowdStrikeReconcileJob
import com.secman.domain.ReconcileJobStatus
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Repository for CrowdStrikeReconcileJob entities.
 */
@Repository
interface CrowdStrikeReconcileJobRepository : JpaRepository<CrowdStrikeReconcileJob, String> {

    /**
     * Find job by ID and username (for authorization on status polling)
     */
    fun findByIdAndUsername(id: String, username: String): Optional<CrowdStrikeReconcileJob>

    /**
     * Find all jobs in any of the given statuses (concurrency guard + stuck-job reclaim)
     */
    fun findByStatusIn(statuses: List<ReconcileJobStatus>): List<CrowdStrikeReconcileJob>
}
