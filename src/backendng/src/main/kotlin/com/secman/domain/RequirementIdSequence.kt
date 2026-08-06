package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "requirement_id_sequence")
@Serdeable
data class RequirementIdSequence(
    @Id
    var id: Long = 1L,

    @Column(name = "next_value", nullable = false)
    var nextValue: Int = 1,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
