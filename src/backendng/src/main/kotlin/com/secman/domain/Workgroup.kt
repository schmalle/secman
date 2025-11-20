package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
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
@Table(
    name = "workgroup",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["parent_id", "name"])
    ],
    indexes = [
        Index(name = "idx_workgroup_parent", columnList = "parent_id"),
        // Query optimization indexes (Feature: Database Structure Optimization)
        Index(name = "idx_workgroup_name", columnList = "name")  // Name lookups and filtering
    ]
)
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
     * Criticality classification level for this workgroup
     * Feature 039: Asset and Workgroup Criticality Classification
     * - NOT NULL with default MEDIUM
     * - Assets inherit this criticality unless they have an explicit override
     * - Higher criticality workgroups take precedence when asset belongs to multiple workgroups
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "criticality", nullable = false, length = 20)
    var criticality: Criticality = Criticality.MEDIUM,

    /**
     * Parent workgroup in the hierarchy
     * Feature 040: Nested Workgroups
     * - NULL indicates root-level workgroup
     * - Self-referential relationship for hierarchy
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    var parent: Workgroup? = null,

    /**
     * Child workgroups in the hierarchy
     * Feature 040: Nested Workgroups
     * - One-to-many relationship (inverse of parent)
     * - CascadeType.PERSIST and MERGE for child management
     */
    @JsonIgnore
    @OneToMany(mappedBy = "parent", cascade = [CascadeType.PERSIST, CascadeType.MERGE], fetch = FetchType.LAZY)
    var children: MutableSet<Workgroup> = mutableSetOf(),

    /**
     * Optimistic locking version
     * Feature 040: Nested Workgroups
     * - Automatically incremented by Hibernate on updates
     * - Prevents concurrent modification conflicts
     */
    @Version
    var version: Long = 0,

    /**
     * Many-to-many relationship with User
     * Users can belong to 0..n workgroups
     * Workgroups can have 0..n users
     * Note: @JsonIgnore prevents circular reference during JSON serialization
     */
    @JsonIgnore
    @ManyToMany(mappedBy = "workgroups", fetch = FetchType.LAZY)
    var users: MutableSet<User> = mutableSetOf(),

    /**
     * Many-to-many relationship with Asset
     * Assets can belong to 0..n workgroups
     * Workgroups can have 0..n assets
     * Note: @JsonIgnore prevents circular reference during JSON serialization
     */
    @JsonIgnore
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

    /**
     * Calculate the depth of this workgroup in the hierarchy.
     * Root-level workgroups have depth 1.
     * Feature 040: Nested Workgroups
     */
    fun calculateDepth(): Int {
        var depth = 1
        var current = this.parent
        while (current != null) {
            depth++
            current = current.parent
            if (depth > 10) break  // Safety limit to prevent infinite loops
        }
        return depth
    }

    /**
     * Get all ancestors from root to immediate parent.
     * Returns empty list for root-level workgroups.
     * Feature 040: Nested Workgroups
     */
    fun getAncestors(): List<Workgroup> {
        val ancestors = mutableListOf<Workgroup>()
        var current = this.parent
        while (current != null) {
            ancestors.add(0, current)  // Prepend to maintain root-to-parent order
            current = current.parent
            if (ancestors.size > 10) break  // Safety limit
        }
        return ancestors
    }

    /**
     * Check if this workgroup is a descendant of the given workgroup.
     * Feature 040: Nested Workgroups
     */
    fun isDescendantOf(potentialAncestor: Workgroup): Boolean {
        var current = this.parent
        while (current != null) {
            if (current.id == potentialAncestor.id) return true
            current = current.parent
        }
        return false
    }

    override fun toString(): String {
        return "Workgroup(id=$id, name='$name', parentId=${parent?.id})"
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
