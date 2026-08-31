package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.micronaut.serde.annotation.Serdeable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.time.Instant

@Entity
@Table(
    name = "users",
    indexes = [
        // Query optimization indexes
        Index(name = "idx_user_email", columnList = "email"),      // Email lookups for OAuth/mappings
        Index(name = "idx_user_username", columnList = "username") // Username lookups
    ]
)
@Serdeable
data class User(
    @Id
    // IDENTITY, not the AUTO default: this table's id column is AUTO_INCREMENT, but on
    // MariaDB Hibernate maps AUTO to a native sequence (<table>_seq) that starts at 1 and
    // knows nothing about rows the database numbered. On any long-lived schema that hands
    // out ids already taken -> "Duplicate entry 'n' for key 'PRIMARY'".
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "role_name")
    var roles: MutableSet<Role> = mutableSetOf(Role.USER),

    /**
     * Many-to-many relationship with Workgroup
     * Users can belong to 0..n workgroups
     *
     * LAZY fetch: Workgroups loaded on-demand to reduce memory for list operations.
     * Use UserRepository.findByIdWithWorkgroups() when workgroups are needed.
     * Feature flag MEMORY_LAZY_LOADING controls service-level behavior.
     *
     * @JsonIgnore prevents LazyInitializationException during JSON serialization.
     * Workgroups should be accessed via service layer, not directly from API responses.
     */
    @JsonIgnore
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_workgroups",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "workgroup_id")]
    )
    var workgroups: MutableSet<Workgroup> = mutableSetOf(),

    @Column(name = "mfa_enabled", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    var mfaEnabled: Boolean = false,

    /**
     * Authentication source tracking
     * Determines if user can change password via self-service
     */
    @Column(name = "auth_source", nullable = false, length = 20)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    var authSource: AuthSource = AuthSource.LOCAL,

    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null,

    /**
     * Last login timestamp
     * Updated on each successful authentication (local or OAuth)
     */
    @Column(name = "last_login")
    var lastLogin: Instant? = null
) {
    /**
     * User roles for access control
     *
     * - USER: Basic authenticated user
     * - ADMIN: Full system access
     * - VULN: Vulnerability management access
     * - RELEASE_MANAGER: Release management access
     * - REQ: Requirements access
     * - RISK: Risk assessment access
     * - SECCHAMPION: Security champion (Risk + Req + Vuln, but NOT Admin)
     * - REQADMIN: Requirements admin (can make decisions on alignment reviews)
     * - REPORT: Receives CLI-triggered email notifications (outdated assets, vulnerabilities)
     */
    enum class Role {
        USER, ADMIN, VULN, RELEASE_MANAGER, REQ, RISK, SECCHAMPION, REQADMIN, REPORT
    }

    /**
     * Authentication source for user accounts
     *
     * - LOCAL: User registered with username/password
     * - OAUTH: User created via OAuth/OIDC provider (no local password)
     * - HYBRID: User has both local password and linked OAuth (future)
     */
    enum class AuthSource {
        LOCAL,
        OAUTH,
        HYBRID
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
     * @return true if user has RISK role
     */
    fun isRisk(): Boolean = hasRole(Role.RISK)

    /**
     * Check if user has REQ role
     * @return true if user has REQ role
     */
    fun isReq(): Boolean = hasRole(Role.REQ)

    /**
     * Check if user has SECCHAMPION role
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