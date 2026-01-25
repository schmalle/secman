package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ReleaseStatus = ReleaseStatus.DRAFT,

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
    enum class ReleaseStatus {
        DRAFT, ACTIVE, LEGACY, PUBLISHED
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