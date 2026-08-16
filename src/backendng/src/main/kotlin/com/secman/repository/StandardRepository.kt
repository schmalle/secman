package com.secman.repository

import com.secman.domain.Standard
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface StandardRepository : JpaRepository<Standard, Long> {
    
    fun findByName(name: String): Optional<Standard>
    
    fun findByNameContainingIgnoreCase(name: String): List<Standard>

    @Query("SELECT s FROM Standard s JOIN s.useCases u WHERE u.id = :usecaseId")
    fun findByUsecaseId(usecaseId: Long): List<Standard>

    fun existsByName(name: String): Boolean

    /**
     * Standard with its use cases eagerly fetched.
     *
     * `Standard.useCases` is a LAZY @ManyToMany, so a caller outside a transaction — the
     * public export endpoints — would hit a LazyInitializationException on access. Returns a
     * list rather than Optional because a fetch join yields one row per joined use case.
     */
    @Query("SELECT DISTINCT s FROM Standard s LEFT JOIN FETCH s.useCases WHERE s.id = :id")
    fun findByIdWithUseCases(id: Long): List<Standard>

    /** As above, matched on the exact name case-insensitively (`IT/OT Security`). */
    @Query("SELECT DISTINCT s FROM Standard s LEFT JOIN FETCH s.useCases WHERE LOWER(s.name) = LOWER(:name)")
    fun findByNameIgnoreCaseWithUseCases(name: String): List<Standard>
}