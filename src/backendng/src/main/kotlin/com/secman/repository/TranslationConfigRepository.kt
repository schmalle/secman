package com.secman.repository

import com.secman.domain.TranslationConfig
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface TranslationConfigRepository : JpaRepository<TranslationConfig, Long> {
    
    fun findByIsActive(isActive: Boolean): List<TranslationConfig>
    
    @Query("SELECT t FROM TranslationConfig t WHERE t.isActive = true")
    fun findActiveConfig(): Optional<TranslationConfig>
    
    @Query("SELECT t FROM TranslationConfig t WHERE t.modelName = :modelName")
    fun findByModelName(modelName: String): List<TranslationConfig>
    
    @Query("SELECT t FROM TranslationConfig t WHERE t.baseUrl = :baseUrl")
    fun findByBaseUrl(baseUrl: String): List<TranslationConfig>
    
    @Query("UPDATE TranslationConfig t SET t.isActive = false WHERE t.isActive = true AND t.id != :excludeId")
    fun deactivateAllExcept(excludeId: Long): Int
    
    @Query("UPDATE TranslationConfig t SET t.isActive = false WHERE t.isActive = true")
    fun deactivateAll(): Int
    
    fun existsByIsActive(isActive: Boolean): Boolean
}