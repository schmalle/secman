package com.secman.repository

import com.secman.domain.UseCase
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface UseCaseRepository : JpaRepository<UseCase, Long> {
    
    fun findByName(name: String): Optional<UseCase>
    
    fun findByNameContainingIgnoreCase(name: String): List<UseCase>
    
    @Query("SELECT u FROM UseCase u WHERE u.isCurrent = true")
    fun findCurrentUseCases(): List<UseCase>
    
    fun existsByName(name: String): Boolean
    
    // Case-insensitive name validation for uniqueness
    @Query("SELECT u FROM UseCase u WHERE LOWER(u.name) = LOWER(:name)")
    fun findByNameIgnoreCase(name: String): Optional<UseCase>
    
    @Query("SELECT u FROM UseCase u WHERE LOWER(u.name) = LOWER(:name) AND u.id != :id")
    fun findByNameIgnoreCaseExcludingId(name: String, id: Long): Optional<UseCase>
    
    // Check if UseCase is associated with Requirements (for deletion validation)
    @Query("SELECT COUNT(r) FROM Requirement r JOIN r.useCases u WHERE u.id = :useCaseId")
    fun countRequirementsByUseCaseId(useCaseId: Long): Long
}