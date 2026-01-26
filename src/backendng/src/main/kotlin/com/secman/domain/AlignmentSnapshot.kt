package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

/**
 * Captures a changed requirement at the moment alignment starts.
 * Feature: 068-requirements-alignment-process
 *
 * Stores the before/after state of requirements that changed between
 * the baseline release and the current release being reviewed. This
 * immutable snapshot ensures the review remains consistent even if
 * requirements are modified after alignment starts.
 */
@Entity
@Table(
    name = "alignment_snapshot",
    indexes = [
        Index(name = "idx_alignment_snapshot_session", columnList = "session_id"),
        Index(name = "idx_alignment_snapshot_requirement", columnList = "requirement_internal_id"),
        Index(name = "idx_alignment_snapshot_change_type", columnList = "change_type")
    ]
)
@Serdeable
data class AlignmentSnapshot(
    @Id
    @GeneratedValue
    var id: Long? = null,

    /**
     * The alignment session this snapshot belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    var session: AlignmentSession,

    /**
     * The internal ID of the requirement (stable identifier across versions).
     */
    @Column(name = "requirement_internal_id", nullable = false, length = 20)
    var requirementInternalId: String,

    /**
     * Type of change detected.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 10)
    var changeType: ChangeType,

    /**
     * Reference to the baseline snapshot (from the ACTIVE release).
     * Null for ADDED requirements.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "baseline_snapshot_id")
    var baselineSnapshot: RequirementSnapshot? = null,

    /**
     * Reference to the current snapshot (from the DRAFT release).
     * Null for DELETED requirements.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_snapshot_id")
    var currentSnapshot: RequirementSnapshot? = null,

    // --- Denormalized fields for display without joins ---

    /**
     * Requirement short description (current or baseline).
     */
    @Column(name = "shortreq", nullable = false, length = 500)
    var shortreq: String,

    /**
     * Previous short description (for MODIFIED only).
     */
    @Column(name = "previous_shortreq", length = 500)
    var previousShortreq: String? = null,

    /**
     * Current details text.
     */
    @Lob
    @Column(name = "details")
    var details: String? = null,

    /**
     * Previous details text (for MODIFIED only).
     */
    @Lob
    @Column(name = "previous_details")
    var previousDetails: String? = null,

    /**
     * Current chapter reference.
     */
    @Column(name = "chapter", length = 50)
    var chapter: String? = null,

    /**
     * Previous chapter reference (for MODIFIED only).
     */
    @Column(name = "previous_chapter", length = 50)
    var previousChapter: String? = null,

    /**
     * Version number in the current release.
     */
    @Column(name = "version_number")
    var versionNumber: Int? = null,

    /**
     * Version number in the baseline release.
     */
    @Column(name = "baseline_version_number")
    var baselineVersionNumber: Int? = null,

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null
) {
    /**
     * Type of requirement change.
     */
    enum class ChangeType {
        /** Requirement is new in this release */
        ADDED,
        /** Requirement content was modified */
        MODIFIED,
        /** Requirement was removed from this release */
        DELETED
    }

    @PrePersist
    fun onCreate() {
        createdAt = Instant.now()
    }

    /**
     * Get a summary of what changed for display.
     */
    fun getChangeSummary(): String {
        return when (changeType) {
            ChangeType.ADDED -> "New requirement added"
            ChangeType.DELETED -> "Requirement removed"
            ChangeType.MODIFIED -> buildModificationSummary()
        }
    }

    private fun buildModificationSummary(): String {
        val changes = mutableListOf<String>()
        if (shortreq != previousShortreq) changes.add("description")
        if (details != previousDetails) changes.add("details")
        if (chapter != previousChapter) changes.add("chapter")
        return if (changes.isEmpty()) {
            "Minor changes"
        } else {
            "Modified: ${changes.joinToString(", ")}"
        }
    }

    override fun toString(): String {
        return "AlignmentSnapshot(id=$id, reqId=$requirementInternalId, changeType=$changeType)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AlignmentSnapshot) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }

    companion object {
        /**
         * Create a snapshot for an ADDED requirement.
         */
        fun forAdded(
            session: AlignmentSession,
            currentSnapshot: RequirementSnapshot
        ): AlignmentSnapshot {
            return AlignmentSnapshot(
                session = session,
                requirementInternalId = currentSnapshot.internalId,
                changeType = ChangeType.ADDED,
                currentSnapshot = currentSnapshot,
                shortreq = currentSnapshot.shortreq,
                details = currentSnapshot.details,
                chapter = currentSnapshot.chapter,
                versionNumber = currentSnapshot.revision
            )
        }

        /**
         * Create a snapshot for a DELETED requirement.
         */
        fun forDeleted(
            session: AlignmentSession,
            baselineSnapshot: RequirementSnapshot
        ): AlignmentSnapshot {
            return AlignmentSnapshot(
                session = session,
                requirementInternalId = baselineSnapshot.internalId,
                changeType = ChangeType.DELETED,
                baselineSnapshot = baselineSnapshot,
                shortreq = baselineSnapshot.shortreq,
                previousShortreq = baselineSnapshot.shortreq,
                details = baselineSnapshot.details,
                previousDetails = baselineSnapshot.details,
                chapter = baselineSnapshot.chapter,
                previousChapter = baselineSnapshot.chapter,
                baselineVersionNumber = baselineSnapshot.revision
            )
        }

        /**
         * Create a snapshot for a MODIFIED requirement.
         */
        fun forModified(
            session: AlignmentSession,
            baselineSnapshot: RequirementSnapshot,
            currentSnapshot: RequirementSnapshot
        ): AlignmentSnapshot {
            return AlignmentSnapshot(
                session = session,
                requirementInternalId = currentSnapshot.internalId,
                changeType = ChangeType.MODIFIED,
                baselineSnapshot = baselineSnapshot,
                currentSnapshot = currentSnapshot,
                shortreq = currentSnapshot.shortreq,
                previousShortreq = baselineSnapshot.shortreq,
                details = currentSnapshot.details,
                previousDetails = baselineSnapshot.details,
                chapter = currentSnapshot.chapter,
                previousChapter = baselineSnapshot.chapter,
                versionNumber = currentSnapshot.revision,
                baselineVersionNumber = baselineSnapshot.revision
            )
        }

        // --- Factory methods for live Requirements (not snapshots) ---

        /**
         * Create a snapshot for an ADDED requirement from a live Requirement entity.
         */
        fun forAddedFromRequirement(
            session: AlignmentSession,
            requirement: Requirement
        ): AlignmentSnapshot {
            return AlignmentSnapshot(
                session = session,
                requirementInternalId = requirement.internalId,
                changeType = ChangeType.ADDED,
                currentSnapshot = null,
                shortreq = requirement.shortreq,
                details = requirement.details,
                chapter = requirement.chapter,
                versionNumber = requirement.versionNumber
            )
        }

        /**
         * Create a snapshot for a MODIFIED requirement from a live Requirement entity.
         */
        fun forModifiedFromRequirement(
            session: AlignmentSession,
            baselineSnapshot: RequirementSnapshot,
            requirement: Requirement
        ): AlignmentSnapshot {
            return AlignmentSnapshot(
                session = session,
                requirementInternalId = requirement.internalId,
                changeType = ChangeType.MODIFIED,
                baselineSnapshot = baselineSnapshot,
                currentSnapshot = null,
                shortreq = requirement.shortreq,
                previousShortreq = baselineSnapshot.shortreq,
                details = requirement.details,
                previousDetails = baselineSnapshot.details,
                chapter = requirement.chapter,
                previousChapter = baselineSnapshot.chapter,
                versionNumber = requirement.versionNumber,
                baselineVersionNumber = baselineSnapshot.revision
            )
        }
    }
}
