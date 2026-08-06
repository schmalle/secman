package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import java.time.Instant

@Entity
@Table(
    name = "requirement_snapshot",
    indexes = [
        Index(name = "idx_snapshot_release", columnList = "release_id"),
        Index(name = "idx_snapshot_original", columnList = "original_requirement_id")
    ]
)
@Serdeable
data class RequirementSnapshot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "release_id", nullable = false)
    var release: Release,

    @Column(name = "original_requirement_id", nullable = false)
    var originalRequirementId: Long,

    @Column(name = "internal_id", nullable = false, length = 20)
    var internalId: String = "",

    @Column(name = "revision", nullable = false)
    var revision: Int = 1,

    @Column(nullable = false)
    @NotBlank
    var shortreq: String,

    @Column(name = "description", columnDefinition = "TEXT")
    var details: String? = null,

    @Column
    var language: String? = null,

    @Column(columnDefinition = "TEXT")
    var example: String? = null,

    @Column(columnDefinition = "TEXT")
    var motivation: String? = null,

    @Column(columnDefinition = "TEXT")
    var usecase: String? = null,

    @Column(name = "norm", columnDefinition = "TEXT")
    var norm: String? = null,

    @Column(name = "chapter", columnDefinition = "TEXT")
    var chapter: String? = null,

    @Column(columnDefinition = "TEXT")
    var usecaseIdsSnapshot: String? = null,  // JSON: [1,2,3]

    @Column(columnDefinition = "TEXT")
    var normIdsSnapshot: String? = null,  // JSON: [1,2,3]

    @Column(name = "snapshot_timestamp", nullable = false, updatable = false)
    var snapshotTimestamp: Instant = Instant.now()
) {
    val idRevision: String
        get() = "$internalId.$revision"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RequirementSnapshot) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0

    override fun toString(): String =
        "RequirementSnapshot(id=$id, release=${release.id}, originalRequirementId=$originalRequirementId, shortreq='${shortreq.take(50)}...')"

    companion object {
        fun fromRequirement(requirement: Requirement, release: Release): RequirementSnapshot {
            return RequirementSnapshot(
                release = release,
                originalRequirementId = requirement.id!!,
                internalId = requirement.internalId,
                revision = requirement.versionNumber,
                shortreq = requirement.shortreq,
                details = requirement.details,
                language = requirement.language,
                example = requirement.example,
                motivation = requirement.motivation,
                usecase = requirement.usecase,
                norm = requirement.norm,
                chapter = requirement.chapter,
                usecaseIdsSnapshot = requirement.usecases.mapNotNull { it.id }.joinToString(",", "[", "]"),
                normIdsSnapshot = requirement.norms.mapNotNull { it.id }.joinToString(",", "[", "]"),
                snapshotTimestamp = Instant.now()
            )
        }
    }
}
