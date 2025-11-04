package com.secman.repository

import com.secman.domain.CrowdStrikeImportHistory
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface CrowdStrikeImportHistoryRepository : JpaRepository<CrowdStrikeImportHistory, Long> {
    @Query("SELECT c FROM CrowdStrikeImportHistory c ORDER BY c.importedAt DESC LIMIT 1")
    fun findLatest(): CrowdStrikeImportHistory?
}
