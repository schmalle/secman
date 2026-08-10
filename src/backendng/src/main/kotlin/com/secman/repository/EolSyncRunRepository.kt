package com.secman.repository

import com.secman.domain.EolSyncRun
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Pageable

@Repository
interface EolSyncRunRepository : JpaRepository<EolSyncRun, Long> {

    @Query("SELECT r FROM EolSyncRun r ORDER BY r.startedAt DESC")
    fun findRecent(pageable: Pageable): List<EolSyncRun>
}
