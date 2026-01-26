package com.secman.repository

import com.secman.domain.AlignmentSnapshot
import com.secman.domain.AlignmentSnapshot.ChangeType
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable

/**
 * Repository for AlignmentSnapshot entity.
 * Feature: 068-requirements-alignment-process
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
     * Find snapshots by change type for a session.
     */
    fun findBySession_IdAndChangeType(sessionId: Long, changeType: ChangeType): List<AlignmentSnapshot>

    /**
     * Find snapshot by session and requirement internal ID.
     */
    fun findBySession_IdAndRequirementInternalId(sessionId: Long, requirementInternalId: String): AlignmentSnapshot?

    /**
     * Count snapshots by change type for a session.
     */
    fun countBySession_IdAndChangeType(sessionId: Long, changeType: ChangeType): Long

    /**
     * Count total snapshots in a session.
     */
    fun countBySession_Id(sessionId: Long): Long

    /**
     * Get change type summary for a session.
     */
    @Query("""
        SELECT new map(
            s.changeType as changeType,
            COUNT(s) as count
        )
        FROM AlignmentSnapshot s
        WHERE s.session.id = :sessionId
        GROUP BY s.changeType
    """)
    fun getChangeTypeSummary(sessionId: Long): List<Map<String, Any>>

    /**
     * Delete all snapshots for a session.
     */
    fun deleteBySession_Id(sessionId: Long)

    /**
     * Find all added requirements in a session.
     */
    @Query("SELECT s FROM AlignmentSnapshot s WHERE s.session.id = :sessionId AND s.changeType = 'ADDED' ORDER BY s.requirementInternalId")
    fun findAddedRequirements(sessionId: Long): List<AlignmentSnapshot>

    /**
     * Find all modified requirements in a session.
     */
    @Query("SELECT s FROM AlignmentSnapshot s WHERE s.session.id = :sessionId AND s.changeType = 'MODIFIED' ORDER BY s.requirementInternalId")
    fun findModifiedRequirements(sessionId: Long): List<AlignmentSnapshot>

    /**
     * Find all deleted requirements in a session.
     */
    @Query("SELECT s FROM AlignmentSnapshot s WHERE s.session.id = :sessionId AND s.changeType = 'DELETED' ORDER BY s.requirementInternalId")
    fun findDeletedRequirements(sessionId: Long): List<AlignmentSnapshot>
}
