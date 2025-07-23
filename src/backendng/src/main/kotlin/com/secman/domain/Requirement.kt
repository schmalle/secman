package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import java.time.Instant

@Entity
@Table(name = "requirement")
@Serdeable
data class Requirement(
    @Id
    @GeneratedValue
    var id: Long? = null,

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

    @Column(name = "Norm", columnDefinition = "TEXT")
    var norm: String? = null,

    @Column(name = "chapter", columnDefinition = "TEXT")
    var chapter: String? = null,

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "requirement_usecase",
        joinColumns = [JoinColumn(name = "requirement_id")],
        inverseJoinColumns = [JoinColumn(name = "usecase_id")]
    )
    var usecases: MutableSet<UseCase> = mutableSetOf(),

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "requirement_norm",
        joinColumns = [JoinColumn(name = "requirement_id")],
        inverseJoinColumns = [JoinColumn(name = "norm_id")]
    )
    var norms: MutableSet<Norm> = mutableSetOf(),

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
        return "Requirement(id=$id, shortreq='$shortreq', language='$language')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Requirement) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}