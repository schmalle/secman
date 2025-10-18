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

    /**
     * Many-to-many relationship with Workgroup
     * Feature: 008-create-an-additional (Workgroup-Based Access Control)
     * Users can belong to 0..n workgroups
     * EAGER fetch: workgroup membership checked on every access control operation
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_workgroups",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "workgroup_id")]
    )
    var workgroups: MutableSet<Workgroup> = mutableSetOf(),

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
) {
    /**
     * User roles for access control
     * Feature: 025-role-based-access-control
     *
     * - USER: Basic authenticated user
     * - ADMIN: Full system access
     * - VULN: Vulnerability management access
     * - RELEASE_MANAGER: Release management access
     * - REQ: Requirements access
     * - RISK: Risk assessment access
     * - SECCHAMPION: Security champion (Risk + Req + Vuln, but NOT Admin)
     */
    enum class Role {
        USER, ADMIN, VULN, RELEASE_MANAGER, REQ, RISK, SECCHAMPION
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

    /**
     * Check if user has RISK role
     * Feature: 025-role-based-access-control
     * @return true if user has RISK role
     */
    fun isRisk(): Boolean = hasRole(Role.RISK)

    /**
     * Check if user has REQ role
     * Feature: 025-role-based-access-control
     * @return true if user has REQ role
     */
    fun isReq(): Boolean = hasRole(Role.REQ)

    /**
     * Check if user has SECCHAMPION role
     * Feature: 025-role-based-access-control
     * @return true if user has SECCHAMPION role
     */
    fun isSecChampion(): Boolean = hasRole(Role.SECCHAMPION)

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