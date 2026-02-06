package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * Admin/REQADMIN decision on an individual requirement review.
 * Feature: 078-release-rework
 *
 * After reviewers submit assessments (OK/CHANGE/NOGO), an admin or REQADMIN
 * user can accept or reject each assessment with an optional comment.
 */
@Entity
@Table(
    name = "review_decision",
    indexes = [
        Index(name = "idx_review_decision_session", columnList = "session_id"),
        Index(name = "idx_review_decision_review", columnList = "review_id")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_review_decision_review",
            columnNames = ["review_id"]
        )
    ]
)
@Serdeable
data class ReviewDecision(
    @Id
    @GeneratedValue
    var id: Long? = null,

    /**
     * The requirement review this decision applies to (OneToOne).
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    var review: RequirementReview,

    /**
     * The alignment session this decision belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    var session: AlignmentSession,

    /**
     * The admin's decision on the reviewer's assessment.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var decision: Decision,

    /**
     * Optional comment from the admin explaining the decision.
     */
    @Lob
    @Column(name = "comment")
    var comment: String? = null,

    /**
     * The user who made this decision.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by", nullable = false)
    var decidedBy: User,

    /**
     * Denormalized username for display without joins.
     */
    @Column(name = "decided_by_username", nullable = false, length = 255)
    var decidedByUsername: String,

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    /**
     * Admin decision values for reviewer assessments.
     */
    enum class Decision {
        /** Assessment is accepted */
        ACCEPTED,
        /** Assessment is rejected */
        REJECTED
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
        return "ReviewDecision(id=$id, reviewId=${review.id}, decision=$decision, decidedBy=$decidedByUsername)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReviewDecision) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}
