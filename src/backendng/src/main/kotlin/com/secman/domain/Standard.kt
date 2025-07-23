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
    @GeneratedValue
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