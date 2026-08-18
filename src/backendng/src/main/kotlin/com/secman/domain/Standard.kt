package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import java.time.Instant

@Entity
@Table(name = "standard")
@Serdeable
data class Standard(
    @Id
    // IDENTITY, not the AUTO default: this table's id column is AUTO_INCREMENT, but on
    // MariaDB Hibernate maps AUTO to a native sequence (<table>_seq) that starts at 1 and
    // knows nothing about rows the database numbered. On any long-lived schema that hands
    // out ids already taken -> "Duplicate entry 'n' for key 'PRIMARY'".
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true)
    @NotBlank
    var name: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @ManyToMany
    @JoinTable(
        name = "standard_usecase",
        joinColumns = [JoinColumn(name = "standard_id")],
        inverseJoinColumns = [JoinColumn(name = "usecase_id")]
    )
    var useCases: MutableSet<UseCase> = mutableSetOf(),

    /**
     * When true this standard covers every requirement, and [useCases] is ignored for selection.
     *
     * The use-case union cannot express "everything": it misses requirements that carry no use
     * case, and it is a snapshot taken at edit time, so requirements added later fall outside the
     * standard until someone re-edits it. Defaults to false so existing standards are unchanged.
     */
    @Column(name = "all_requirements", nullable = false)
    var allRequirements: Boolean = false,

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) : VersionedEntity() {

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
        return "Standard(id=$id, name='$name')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Standard) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}