package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Converts between ReleaseStatus enum and database string values.
 * Handles legacy status names (DRAFT, IN_REVIEW, LEGACY, PUBLISHED) for backward
 * compatibility during migration, mapping them to the new status names.
 */
@Converter
class ReleaseStatusConverter : AttributeConverter<Release.ReleaseStatus, String> {
    override fun convertToDatabaseColumn(attribute: Release.ReleaseStatus?): String? {
        return attribute?.name
    }

    override fun convertToEntityAttribute(dbData: String?): Release.ReleaseStatus? {
        if (dbData == null) return null
        return when (dbData) {
            "DRAFT" -> Release.ReleaseStatus.PREPARATION
            "IN_REVIEW" -> Release.ReleaseStatus.ALIGNMENT
            "LEGACY", "PUBLISHED" -> Release.ReleaseStatus.ARCHIVED
            else -> Release.ReleaseStatus.valueOf(dbData)
        }
    }
}

@Entity
@Table(name = "releases")
@Serdeable
data class Release(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(unique = true, length = 50, nullable = false)
    @NotBlank
    @Size(max = 50)
    var version: String,

    @Column(nullable = false)
    @NotBlank
    var name: String,

    @Lob
    var description: String? = null,

    @Convert(converter = ReleaseStatusConverter::class)
    @Column(nullable = false)
    var status: ReleaseStatus = ReleaseStatus.PREPARATION,

    @Column(name = "release_date")
    var releaseDate: Instant? = null,

    @ManyToOne
    @JoinColumn(name = "created_by")
    var createdBy: User? = null,

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    /**
     * Release lifecycle statuses
     * Feature: 078-release-rework
     *
     * - PREPARATION: Initial state, can be edited, alignment can be started
     * - ALIGNMENT: Alignment process active, requirements under review
     * - ACTIVE: Current active release (only one can be ACTIVE at a time)
     * - ARCHIVED: Previously active release, replaced by newer ACTIVE
     */
    enum class ReleaseStatus {
        PREPARATION, ALIGNMENT, ACTIVE, ARCHIVED
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
        return "Release(id=$id, version='$version', name='$name', status=$status)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Release) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}