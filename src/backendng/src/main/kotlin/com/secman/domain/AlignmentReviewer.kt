package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Tracks each reviewer's participation in an alignment session.
 * Feature: 068-requirements-alignment-process
 *
 * Each user with REQ role is assigned as a reviewer when an alignment
 * session is started. A unique review token is generated for secure
 * email-based access to the review page.
 */
@Entity
@Table(
    name = "alignment_reviewer",
    indexes = [
        Index(name = "idx_alignment_reviewer_session", columnList = "session_id"),
        Index(name = "idx_alignment_reviewer_user", columnList = "user_id"),
        Index(name = "idx_alignment_reviewer_token", columnList = "review_token")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_alignment_reviewer_session_user", columnNames = ["session_id", "user_id"]),
        UniqueConstraint(name = "uk_alignment_reviewer_token", columnNames = ["review_token"])
    ]
)
@Serdeable
data class AlignmentReviewer(
    @Id
    @GeneratedValue
    var id: Long? = null,

    /**
     * The alignment session this reviewer belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    var session: AlignmentSession,

    /**
     * The user assigned as reviewer.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    /**
     * Current review status for this reviewer.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ReviewerStatus = ReviewerStatus.PENDING,

    /**
     * Secure token for email-based access to the review page.
     * Format: UUID v4, expires when session is completed.
     */
    @Column(name = "review_token", nullable = false, length = 36)
    var reviewToken: String = UUID.randomUUID().toString(),

    /**
     * When the reviewer started their review (first page access).
     */
    @Column(name = "started_at")
    var startedAt: Instant? = null,

    /**
     * When the reviewer submitted their final review.
     */
    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    /**
     * Number of requirements reviewed by this reviewer.
     */
    @Column(name = "reviewed_count", nullable = false)
    var reviewedCount: Int = 0,

    /**
     * When the notification email was sent.
     */
    @Column(name = "notified_at")
    var notifiedAt: Instant? = null,

    /**
     * Count of reminder emails sent to this reviewer.
     */
    @Column(name = "reminder_count", nullable = false)
    var reminderCount: Int = 0,

    /**
     * When the last reminder was sent.
     */
    @Column(name = "last_reminder_at")
    var lastReminderAt: Instant? = null,

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    /**
     * Reviewer status values.
     */
    enum class ReviewerStatus {
        /** Reviewer has been notified but hasn't started */
        PENDING,
        /** Reviewer has started but not completed their review */
        IN_PROGRESS,
        /** Reviewer has completed their review */
        COMPLETED
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

    /**
     * Mark that the reviewer has started their review.
     */
    fun markStarted() {
        if (status == ReviewerStatus.PENDING) {
            status = ReviewerStatus.IN_PROGRESS
            startedAt = Instant.now()
        }
    }

    /**
     * Mark the review as completed.
     */
    fun markCompleted() {
        status = ReviewerStatus.COMPLETED
        completedAt = Instant.now()
    }

    /**
     * Record that a notification email was sent.
     */
    fun markNotified() {
        notifiedAt = Instant.now()
    }

    /**
     * Record that a reminder email was sent.
     */
    fun markReminded() {
        reminderCount++
        lastReminderAt = Instant.now()
    }

    override fun toString(): String {
        return "AlignmentReviewer(id=$id, sessionId=${session.id}, userId=${user.id}, status=$status)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AlignmentReviewer) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}
