package com.secman.repository

import com.secman.domain.AlignmentSession
import com.secman.domain.AlignmentSession.AlignmentStatus
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

/**
 * Repository for AlignmentSession entity.
 * Feature: 068-requirements-alignment-process
 */
@Repository
interface AlignmentSessionRepository : JpaRepository<AlignmentSession, Long> {

    /**
     * Find alignment session by release ID.
     */
    fun findByRelease_Id(releaseId: Long): Optional<AlignmentSession>

    /**
     * Find all sessions for a release.
     */
    fun findAllByRelease_Id(releaseId: Long): List<AlignmentSession>

    /**
     * Find active (OPEN) session for a release.
     */
    @Query("SELECT s FROM AlignmentSession s WHERE s.release.id = :releaseId AND s.status = 'OPEN'")
    fun findOpenSessionByReleaseId(releaseId: Long): Optional<AlignmentSession>

    /**
     * Find sessions by status.
     */
    fun findByStatus(status: AlignmentStatus): List<AlignmentSession>

    /**
     * Find sessions initiated by a specific user.
     */
    fun findByInitiatedBy_Id(userId: Long): List<AlignmentSession>

    /**
     * Check if a release has an open alignment session.
     */
    @Query("SELECT COUNT(s) > 0 FROM AlignmentSession s WHERE s.release.id = :releaseId AND s.status = 'OPEN'")
    fun hasOpenSession(releaseId: Long): Boolean

    /**
     * Find all sessions ordered by creation date (newest first).
     */
    @Query("SELECT s FROM AlignmentSession s ORDER BY s.createdAt DESC")
    fun findAllOrderByCreatedAtDesc(): List<AlignmentSession>
}
