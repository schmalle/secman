package com.secman.repository

import com.secman.domain.EolRelease
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Pageable

@Repository
interface EolReleaseRepository : JpaRepository<EolRelease, Long> {

    fun findByEolProductId(eolProductId: Long): List<EolRelease>

    fun findByEolProductIdAndCycle(eolProductId: Long, cycle: String): EolRelease?

    @Query("DELETE FROM EolRelease r WHERE r.eolProductId = :eolProductId")
    fun deleteByEolProductId(eolProductId: Long): Int

    @Query("SELECT r FROM EolRelease r ORDER BY r.eolProductId ASC, r.cycle ASC")
    fun findAllOrdered(pageable: Pageable): List<EolRelease>

    @Query("SELECT COUNT(r) FROM EolRelease r")
    fun countAll(): Long
}
