package com.secman.repository

import com.secman.domain.EmailConfig
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface EmailConfigRepository : JpaRepository<EmailConfig, Long> {
    
    fun findByIsActive(isActive: Boolean): List<EmailConfig>
    
    @Query("SELECT e FROM EmailConfig e WHERE e.isActive = true")
    fun findActiveConfig(): Optional<EmailConfig>
    
    @Query("SELECT e FROM EmailConfig e WHERE e.smtpHost = :host AND e.smtpPort = :port")
    fun findBySmtpHostAndPort(host: String, port: Int): List<EmailConfig>
    
    @Query("SELECT e FROM EmailConfig e WHERE e.fromEmail = :email")
    fun findByFromEmail(email: String): List<EmailConfig>
    
    @Query("UPDATE EmailConfig e SET e.isActive = false WHERE e.isActive = true AND e.id != :excludeId")
    fun deactivateAllExcept(excludeId: Long): Int
    
    @Query("UPDATE EmailConfig e SET e.isActive = false WHERE e.isActive = true")
    fun deactivateAll(): Int
}