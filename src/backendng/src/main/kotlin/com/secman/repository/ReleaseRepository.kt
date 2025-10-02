package com.secman.repository

import com.secman.domain.Release
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface ReleaseRepository : JpaRepository<Release, Long> {
    
    fun findByVersion(version: String): Optional<Release>
    
    fun findByNameContainingIgnoreCase(name: String): List<Release>
    
    fun findByStatus(status: Release.ReleaseStatus): List<Release>
    
    fun findByCreatedBy_Id(createdById: Long): List<Release>
    
    fun existsByVersion(version: String): Boolean
    
    @Query("SELECT r FROM Release r ORDER BY r.createdAt DESC")
    fun findAllOrderByCreatedAtDesc(): List<Release>
}