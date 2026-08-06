package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import java.time.Instant

@Entity
@Table(name = "usecase")
@Serdeable
data class UseCase(
    @Id
    // IDENTITY, not the AUTO default: this table's id column is AUTO_INCREMENT, but on
    // MariaDB Hibernate maps AUTO to a native sequence (<table>_seq) that starts at 1 and
    // knows nothing about rows the database numbered. On any long-lived schema that hands
    // out ids already taken -> "Duplicate entry 'n' for key 'PRIMARY'".
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(unique = true, nullable = false)
    @NotBlank
    var name: String,

    @Column(name = "system_protected", nullable = false)
    var systemProtected: Boolean = false,

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
        return "UseCase(id=$id, name='$name')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UseCase) return false
        return name == other.name
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }
}