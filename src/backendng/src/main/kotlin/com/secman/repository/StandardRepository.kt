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
    
    @Query("SELECT s FROM Standard s WHERE s.isCurrent = true")
    fun findCurrentStandards(): List<Standard>
    
    @Query("SELECT s FROM Standard s JOIN s.useCases u WHERE u.id = :usecaseId")
    fun findByUsecaseId(usecaseId: Long): List<Standard>
    
    fun existsByName(name: String): Boolean
}