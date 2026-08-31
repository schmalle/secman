package com.secman.repository

import com.secman.domain.AlignmentSnapshot
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable

/**
 * Repository for AlignmentSnapshot entity.
 */
@Repository
interface AlignmentSnapshotRepository : JpaRepository<AlignmentSnapshot, Long> {

    /**
     * Find all snapshots for a session.
     */
    fun findBySession_Id(sessionId: Long): List<AlignmentSnapshot>

    /**
     * Find all snapshots for a session with pagination.
     */
    fun findBySession_Id(sessionId: Long, pageable: Pageable): Page<AlignmentSnapshot>

    /**
     * Count total snapshots in a session.
     */
    fun countBySession_Id(sessionId: Long): Long

    /**
     * Delete all snapshots for a session.
     */
    fun deleteBySession_Id(sessionId: Long)
}
