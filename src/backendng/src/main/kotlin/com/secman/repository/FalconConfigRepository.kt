package com.secman.repository

import com.secman.domain.FalconConfig
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface FalconConfigRepository : JpaRepository<FalconConfig, Long> {
    
    fun findByIsActive(isActive: Boolean): List<FalconConfig>
    
    @Query("SELECT f FROM FalconConfig f WHERE f.isActive = true")
    fun findActiveConfig(): Optional<FalconConfig>
    
    @Query("UPDATE FalconConfig f SET f.isActive = false WHERE f.isActive = true AND f.id != :excludeId")
    fun deactivateAllExcept(excludeId: Long): Int
    
    @Query("UPDATE FalconConfig f SET f.isActive = false WHERE f.isActive = true")
    fun deactivateAll(): Int
}
