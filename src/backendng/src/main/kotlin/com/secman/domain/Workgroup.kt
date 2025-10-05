package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Workgroup entity - represents an organizational unit or team grouping
 * Feature: 008-create-an-additional (Workgroup-Based Access Control)
 *
 * Related Requirements:
 * - FR-001: System MUST allow administrators to create workgroups with a unique name
 * - FR-006: Workgroup names MUST be 1-100 characters, alphanumeric + spaces + hyphens, unique (case-insensitive)
 */
@Entity
@Table(name = "workgroup")
@Serdeable
data class Workgroup(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, length = 100)
    @NotBlank(message = "Workgroup name is required")
    @Size(min = 1, max = 100, message = "Workgroup name must be 1-100 characters")
    @Pattern(
        regexp = "^[a-zA-Z0-9 -]+$",
        message = "Workgroup name must contain only letters, numbers, spaces, and hyphens"
    )
    var name: String,

    @Column(length = 512)
    @Size(max = 512, message = "Description must not exceed 512 characters")
    var description: String? = null,

    /**
     * Many-to-many relationship with User
     * Users can belong to 0..n workgroups
     * Workgroups can have 0..n users
     */
    @ManyToMany(mappedBy = "workgroups", fetch = FetchType.LAZY)
    var users: MutableSet<User> = mutableSetOf(),

    /**
     * Many-to-many relationship with Asset
     * Assets can belong to 0..n workgroups
     * Workgroups can have 0..n assets
     */
    @ManyToMany(mappedBy = "workgroups", fetch = FetchType.LAZY)
    var assets: MutableSet<Asset> = mutableSetOf(),

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
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
        return "Workgroup(id=$id, name='$name', description='$description')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Workgroup) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}
