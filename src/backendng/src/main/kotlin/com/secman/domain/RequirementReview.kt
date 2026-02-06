package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * Individual feedback on a requirement change within an alignment session.
 * Feature: 068-requirements-alignment-process
 *
 * Each reviewer can provide an assessment (OK/Change/NOGO) and optional
 * comments for each changed requirement in the alignment session.
 */
@Entity
@Table(
    name = "requirement_review",
    indexes = [
        Index(name = "idx_requirement_review_session", columnList = "session_id"),
        Index(name = "idx_requirement_review_reviewer", columnList = "reviewer_id"),
        Index(name = "idx_requirement_review_snapshot", columnList = "snapshot_id")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_requirement_review_reviewer_snapshot",
            columnNames = ["reviewer_id", "snapshot_id"]
        )
    ]
)
@Serdeable
data class RequirementReview(
    @Id
    @GeneratedValue
    var id: Long? = null,

    /**
     * The alignment session this review belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    var session: AlignmentSession,

    /**
     * The reviewer who submitted this feedback.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", nullable = false)
    var reviewer: AlignmentReviewer,

    /**
     * The alignment snapshot being reviewed.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id", nullable = false)
    var snapshot: AlignmentSnapshot,

    /**
     * The reviewer's assessment of this requirement change.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var assessment: ReviewAssessment,

    /**
     * Optional free-text comment explaining the assessment.
     */
    @Lob
    @Column(name = "comment")
    var comment: String? = null,

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    /**
     * Assessment values for requirement changes.
     *
     * - OK: Change is acceptable
     * - CHANGE: Change request, needs rework
     * - NOGO: Not acceptable, blocks release
     */
    enum class ReviewAssessment {
        /** Change is acceptable */
        OK,
        /** Change request, needs rework */
        CHANGE,
        /** Not acceptable, blocks release */
        NOGO
    }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    override fun toString(): String {
        return "RequirementReview(id=$id, sessionId=${session.id}, reviewerId=${reviewer.id}, assessment=$assessment)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RequirementReview) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}
