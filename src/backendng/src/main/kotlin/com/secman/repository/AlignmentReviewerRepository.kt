package com.secman.repository

import com.secman.domain.AlignmentReviewer
import com.secman.domain.AlignmentReviewer.ReviewerStatus
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

/**
 * Repository for AlignmentReviewer entity.
 * Feature: 068-requirements-alignment-process
 */
@Repository
interface AlignmentReviewerRepository : JpaRepository<AlignmentReviewer, Long> {

    /**
     * Find all reviewers for a session.
     */
    fun findBySession_Id(sessionId: Long): List<AlignmentReviewer>

    /**
     * Find reviewer by session and user.
     */
    fun findBySession_IdAndUser_Id(sessionId: Long, userId: Long): Optional<AlignmentReviewer>

    /**
     * Find reviewer by unique review token.
     */
    fun findByReviewToken(reviewToken: String): Optional<AlignmentReviewer>

    /**
     * Find reviewers by status within a session.
     */
    fun findBySession_IdAndStatus(sessionId: Long, status: ReviewerStatus): List<AlignmentReviewer>

    /**
     * Count reviewers by status within a session.
     */
    fun countBySession_IdAndStatus(sessionId: Long, status: ReviewerStatus): Long

    /**
     * Count total reviewers in a session.
     */
    fun countBySession_Id(sessionId: Long): Long

    /**
     * Find incomplete reviewers (not COMPLETED) for a session.
     */
    @Query("SELECT r FROM AlignmentReviewer r WHERE r.session.id = :sessionId AND r.status != 'COMPLETED'")
    fun findIncompleteReviewers(sessionId: Long): List<AlignmentReviewer>

    /**
     * Find all reviews by a specific user across all sessions.
     */
    fun findByUser_Id(userId: Long): List<AlignmentReviewer>

    /**
     * Check if a user is a reviewer for a session.
     */
    @Query("SELECT COUNT(r) > 0 FROM AlignmentReviewer r WHERE r.session.id = :sessionId AND r.user.id = :userId")
    fun isUserReviewer(sessionId: Long, userId: Long): Boolean

    /**
     * Delete all reviewers for a session.
     */
    fun deleteBySession_Id(sessionId: Long)
}
