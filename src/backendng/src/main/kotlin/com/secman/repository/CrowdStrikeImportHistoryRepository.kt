package com.secman.repository

import com.secman.domain.CrowdStrikeImportHistory
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Pageable

@Repository
interface CrowdStrikeImportHistoryRepository : JpaRepository<CrowdStrikeImportHistory, Long> {
    @Query("SELECT c FROM CrowdStrikeImportHistory c ORDER BY c.importedAt DESC LIMIT 1")
    fun findLatest(): CrowdStrikeImportHistory?

    /**
     * Most recent import-history rows, newest first. Used by the host diagnostic
     * endpoint to correlate a host's missing rows with recent import activity.
     */
    @Query("SELECT c FROM CrowdStrikeImportHistory c ORDER BY c.importedAt DESC")
    fun findRecent(pageable: Pageable): List<CrowdStrikeImportHistory>
}
