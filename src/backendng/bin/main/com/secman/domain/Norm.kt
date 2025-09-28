package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import java.util.*

@Entity
@Table(name = "norm")
@Serdeable
data class Norm(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(unique = true, nullable = false)
    @NotBlank
    var name: String,

    @Column
    var version: String = "",

    @Column
    var year: Int? = null,

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created_at", updatable = false)
    var createdAt: Date? = null,

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "updated_at")
    var updatedAt: Date? = null
) : VersionedEntity() {

    @PrePersist
    fun onCreate() {
        val now = Date()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Date()
    }

    override fun toString(): String {
        return "Norm(id=$id, name='$name', version='$version', year=$year)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Norm) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}