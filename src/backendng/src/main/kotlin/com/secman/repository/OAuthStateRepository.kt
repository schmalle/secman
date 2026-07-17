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

    /**
     * Deletes the state row and reports how many rows were removed. The count makes the
     * delete usable as an atomic single-use claim: exactly one of two concurrent callbacks
     * carrying the same state gets 1, the other gets 0.
     */
    @Query("DELETE FROM OAuthState o WHERE o.stateToken = :stateToken")
    fun deleteByStateToken(stateToken: String): Int

    /**
     * Count active states for a provider (for observability/debugging)
     */
    fun countByProviderId(providerId: Long): Long
}