package com.secman.repository

import com.secman.domain.EolSyncRun
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Pageable
import java.util.Optional

@Repository
interface EolSyncRunRepository : JpaRepository<EolSyncRun, Long> {

    @Query("SELECT r FROM EolSyncRun r ORDER BY r.startedAt DESC")
    fun findRecent(pageable: Pageable): List<EolSyncRun>

    /**
     * Look up one run by its client-facing handle.
     *
     * Derived query, so the value is bound rather than concatenated (§A03) —
     * `runId` reaches this straight from a path variable.
     */
    fun findByRunId(runId: String): Optional<EolSyncRun>

    /**
     * Backs the single-run-at-a-time guard.
     *
     * Ordered by id rather than startedAt so two triggers landing in the same
     * millisecond still agree on which one won — startedAt can tie, the
     * identity column cannot. Bounded in practice by that same guard: a
     * healthy system holds at most one RUNNING row.
     */
    fun findByStatusOrderByIdAsc(status: String): List<EolSyncRun>
}
