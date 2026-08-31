package com.secman.repository

import com.secman.domain.RequirementReview
import com.secman.domain.RequirementReview.ReviewAssessment
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

/**
 * Repository for RequirementReview entity.
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
     * Count total reviews submitted by a reviewer.
     */
    fun countByReviewer_Id(reviewerId: Long): Long

    /**
     * Count total reviews for a session.
     */
    fun countBySession_Id(sessionId: Long): Long

    /**
     * Delete all reviews for a session.
     */
    fun deleteBySession_Id(sessionId: Long)

    /**
     * Delete all reviews by a specific reviewer.
     */
    fun deleteByReviewer_Id(reviewerId: Long)
}
