package com.secman.repository

import com.secman.domain.CrowdStrikeSeverityHistory
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

/**
 * Repository for the severity-history table used by the CrowdStrike reconcile
 * sweep. The natural ID is the severity string itself (already uppercased).
 *
 * Callers should use `findAll()` to read the full historical union; writes go
 * through [upsertSeverity] (atomic, safe under concurrent reconcile runs).
 */
@Repository
interface CrowdStrikeSeverityHistoryRepository : JpaRepository<CrowdStrikeSeverityHistory, String> {

    /**
     * Atomic upsert of one severity row. The previous findById-then-save pattern raced under
     * concurrent CLI workers: both saw "no row" for a new severity, both inserted, and the
     * loser's primary-key violation rolled back its entire reconcile transaction.
     */
    @Query(
        value = """
            INSERT INTO crowdstrike_severity_history (severity, first_seen_at, last_seen_at)
            VALUES (:severity, :now, :now)
            ON DUPLICATE KEY UPDATE last_seen_at = VALUES(last_seen_at)
        """,
        nativeQuery = true
    )
    fun upsertSeverity(severity: String, now: LocalDateTime): Int
}
