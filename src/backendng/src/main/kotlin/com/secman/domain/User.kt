package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.time.Instant

@Entity
@Table(name = "users")
@Serdeable
data class User(
    @Id
    @GeneratedValue
    var id: Long? = null,

    @Column(unique = true, nullable = false)
    @NotBlank
    var username: String,

    @Column(unique = true, nullable = false)
    @Email
    @NotBlank
    var email: String,

    @Column(name = "password_hash", nullable = false)
    @JsonIgnore
    @NotBlank
    var passwordHash: String,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = [JoinColumn(name = "user_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "role_name")
    var roles: MutableSet<Role> = mutableSetOf(Role.USER),

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    enum class Role {
        USER, ADMIN
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

    fun hasRole(role: Role): Boolean = roles.contains(role)

    fun isAdmin(): Boolean = hasRole(Role.ADMIN)

    override fun toString(): String {
        return "User(id=$id, username='$username', email='$email', roles=$roles)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is User) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}