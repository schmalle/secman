package com.secman.repository

import com.secman.domain.ReviewDecision
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

/**
 * Repository for ReviewDecision entity.
 * Feature: 078-release-rework
 */
@Repository
interface ReviewDecisionRepository : JpaRepository<ReviewDecision, Long> {

    /**
     * Find decision for a specific review.
     */
    fun findByReview_Id(reviewId: Long): Optional<ReviewDecision>

    /**
     * Find all decisions for a session.
     */
    fun findBySession_Id(sessionId: Long): List<ReviewDecision>

    /**
     * Delete all decisions made by a specific user.
     */
    fun deleteByDecidedBy_Id(userId: Long)

    /**
     * Delete all decisions for reviews belonging to a specific reviewer.
     */
    @Query("DELETE FROM ReviewDecision d WHERE d.review.reviewer.id = :reviewerId")
    fun deleteByReviewReviewerId(reviewerId: Long)
}
