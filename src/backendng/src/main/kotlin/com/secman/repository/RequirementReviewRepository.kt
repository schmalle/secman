package com.secman.repository

import com.secman.domain.RequirementReview
import com.secman.domain.RequirementReview.ReviewAssessment
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

/**
 * Repository for RequirementReview entity.
 * Feature: 068-requirements-alignment-process
 */
@Repository
interface RequirementReviewRepository : JpaRepository<RequirementReview, Long> {

    /**
     * Find all reviews for a session.
     */
    fun findBySession_Id(sessionId: Long): List<RequirementReview>

    /**
     * Find all reviews by a specific reviewer.
     */
    fun findByReviewer_Id(reviewerId: Long): List<RequirementReview>

    /**
     * Find all reviews for a specific snapshot (requirement).
     */
    fun findBySnapshot_Id(snapshotId: Long): List<RequirementReview>

    /**
     * Find review by reviewer and snapshot (unique).
     */
    fun findByReviewer_IdAndSnapshot_Id(reviewerId: Long, snapshotId: Long): Optional<RequirementReview>

    /**
     * Count reviews by assessment type for a session.
     */
    fun countBySession_IdAndAssessment(sessionId: Long, assessment: ReviewAssessment): Long

    /**
     * Count reviews by assessment type for a specific snapshot.
     */
    fun countBySnapshot_IdAndAssessment(snapshotId: Long, assessment: ReviewAssessment): Long

    /**
     * Count total reviews submitted by a reviewer.
     */
    fun countByReviewer_Id(reviewerId: Long): Long

    /**
     * Count total reviews for a session.
     */
    fun countBySession_Id(sessionId: Long): Long

    /**
     * Get assessment summary for a snapshot.
     * Returns counts of MINOR, MAJOR, NOK assessments.
     */
    @Query("""
        SELECT new map(
            r.assessment as assessment,
            COUNT(r) as count
        )
        FROM RequirementReview r
        WHERE r.snapshot.id = :snapshotId
        GROUP BY r.assessment
    """)
    fun getAssessmentSummary(snapshotId: Long): List<Map<String, Any>>

    /**
     * Find reviews with NOK assessment for a session.
     */
    @Query("SELECT r FROM RequirementReview r WHERE r.session.id = :sessionId AND r.assessment = 'NOK'")
    fun findNokReviews(sessionId: Long): List<RequirementReview>

    /**
     * Delete all reviews for a session.
     */
    fun deleteBySession_Id(sessionId: Long)

    /**
     * Delete all reviews by a specific reviewer.
     */
    fun deleteByReviewer_Id(reviewerId: Long)
}
