package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * Represents an active alignment process for a release.
 * Feature: 068-requirements-alignment-process
 *
 * An alignment session is created when a Release Manager initiates the
 * requirements alignment process for a DRAFT release. All users with REQ role
 * are invited to review requirement changes since the last ACTIVE release.
 */
@Entity
@Table(
    name = "alignment_session",
    indexes = [
        Index(name = "idx_alignment_session_release", columnList = "release_id"),
        Index(name = "idx_alignment_session_status", columnList = "status")
    ]
)
@Serdeable
data class AlignmentSession(
    @Id
    @GeneratedValue
    var id: Long? = null,

    /**
     * The release this alignment session is for.
     * A release can only have one active alignment session at a time.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "release_id", nullable = false)
    var release: Release,

    /**
     * Current status of the alignment session.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AlignmentStatus = AlignmentStatus.OPEN,

    /**
     * User who initiated the alignment process.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initiated_by", nullable = false)
    var initiatedBy: User,

    /**
     * Reference to the baseline release for comparison.
     * This is the last ACTIVE release at the time alignment was started.
     * Null if this is the first release (no baseline).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "baseline_release_id")
    var baselineRelease: Release? = null,

    /**
     * Total count of changed requirements at the time of session creation.
     */
    @Column(name = "changed_requirements_count", nullable = false)
    var changedRequirementsCount: Int = 0,

    /**
     * When the alignment session was started.
     */
    @Column(name = "started_at", nullable = false, updatable = false)
    var startedAt: Instant? = null,

    /**
     * When the alignment session was completed (finalized or cancelled).
     */
    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    /**
     * Optional notes from the Release Manager when finalizing/cancelling.
     */
    @Lob
    @Column(name = "completion_notes")
    var completionNotes: String? = null,

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    /**
     * Alignment session status values.
     */
    enum class AlignmentStatus {
        /** Session is active, reviewers can submit feedback */
        OPEN,
        /** Session completed successfully, release can be activated */
        COMPLETED,
        /** Session was cancelled by Release Manager */
        CANCELLED
    }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
        startedAt = startedAt ?: now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    /**
     * Check if the session is still open for review submissions.
     */
    fun isOpen(): Boolean = status == AlignmentStatus.OPEN

    /**
     * Mark the session as completed.
     */
    fun complete(notes: String? = null) {
        status = AlignmentStatus.COMPLETED
        completedAt = Instant.now()
        completionNotes = notes
    }

    /**
     * Mark the session as cancelled.
     */
    fun cancel(notes: String? = null) {
        status = AlignmentStatus.CANCELLED
        completedAt = Instant.now()
        completionNotes = notes
    }

    override fun toString(): String {
        return "AlignmentSession(id=$id, releaseId=${release.id}, status=$status)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AlignmentSession) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}
