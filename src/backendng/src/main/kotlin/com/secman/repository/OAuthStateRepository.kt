package com.secman.repository

import com.secman.domain.OAuthState
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.annotation.Query
import java.time.LocalDateTime
import java.util.*

@Repository
interface OAuthStateRepository : JpaRepository<OAuthState, Long> {
    
    fun findByStateValue(stateValue: String): Optional<OAuthState>
    
    @Query("DELETE FROM OAuthState o WHERE o.expiresAt < :now")
    fun deleteExpiredStates(now: LocalDateTime = LocalDateTime.now())
    
    fun deleteByStateValue(stateValue: String)
}