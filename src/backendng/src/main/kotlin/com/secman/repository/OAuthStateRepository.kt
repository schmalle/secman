package com.secman.repository

import com.secman.domain.OAuthState
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.annotation.Query
import java.time.LocalDateTime
import java.util.*

@Repository
interface OAuthStateRepository : JpaRepository<OAuthState, Long> {

    fun findByStateToken(stateToken: String): Optional<OAuthState>

    @Query("DELETE FROM OAuthState o WHERE o.expiresAt < :now")
    fun deleteExpiredStates(now: LocalDateTime = LocalDateTime.now())

    fun deleteByStateToken(stateToken: String)

    /**
     * Delete all OAuth states for a specific provider.
     * Used to clean up stale states before generating new ones,
     * preventing "state" errors in corporate AAD environments.
     */
    fun deleteByProviderId(providerId: Long)

    /**
     * Count existing states for a provider (for debugging/logging)
     */
    fun countByProviderId(providerId: Long): Long
}