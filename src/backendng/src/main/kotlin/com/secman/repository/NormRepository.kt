package com.secman.repository

import com.secman.domain.Norm
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface NormRepository : JpaRepository<Norm, Long> {
    
    fun findByName(name: String): Optional<Norm>
    
    fun findByNameContainingIgnoreCase(name: String): List<Norm>

    fun findByVersion(version: String): List<Norm>

    fun existsByName(name: String): Boolean
}