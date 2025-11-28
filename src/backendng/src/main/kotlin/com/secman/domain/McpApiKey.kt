package com.secman.domain

import jakarta.persistence.*
import jakarta.validation.constraints.*
import java.time.LocalDateTime
import io.micronaut.serde.annotation.Serdeable

/**
 * MCP API Key entity for managing API keys used for MCP client authentication.
 *
 * Each API key belongs to a user and has specific permissions that determine
 * what MCP operations the key can authorize.
 */
@Entity
@Table(
    name = "mcp_api_keys",
    indexes = [
        Index(name = "idx_mcp_api_key_id", columnList = "keyId", unique = true),
        Index(name = "idx_mcp_api_keys_user_active", columnList = "userId, isActive"),
        Index(name = "idx_mcp_api_keys_expiry", columnList = "expiresAt")
    ]
)
@Serdeable
data class McpApiKey(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    /**
     * Public identifier for the API key. This is what clients include in requests.
     * Should be cryptographically secure and unique across all keys.
     */
    @Column(name = "key_id", nullable = false, unique = true, length = 64)
    @NotBlank
    @Size(min = 16, max = 64)
    val keyId: String,

    /**
     * Hashed version of the API key secret.
     * The raw key is only shown once during creation.
     */
    @Column(name = "key_hash", nullable = false, length = 255)
    @NotBlank
    @Size(max = 255)
    val keyHash: String,

    /**
     * Human-readable name for the API key.
     * Must be unique per user to avoid confusion.
     */
    @Column(name = "name", nullable = false, length = 100)
    @NotBlank
    @Size(min = 1, max = 100)
    val name: String,

    /**
     * Foreign key reference to the User who owns this API key.
     */
    @Column(name = "user_id", nullable = false)
    @NotNull
    val userId: Long,

    /**
     * Set of MCP permissions this key is authorized for.
     * Stored as comma-separated enum values.
     */
    @Column(name = "permissions", nullable = false, length = 500)
    @NotBlank
    @Size(max = 500)
    val permissions: String, // Comma-separated McpPermission enum values

    /**
     * Timestamp when the API key was created.
     */
    @Column(name = "created_at", nullable = false)
    @NotNull
    val createdAt: LocalDateTime = LocalDateTime.now(),

    /**
     * Timestamp when the API key was last used for authentication.
     * Null if never used.
     */
    @Column(name = "last_used_at")
    var lastUsedAt: LocalDateTime? = null,

    /**
     * Optional expiration timestamp.
     * If null, the key does not expire.
     */
    @Column(name = "expires_at")
    val expiresAt: LocalDateTime? = null,

    /**
     * Whether the API key is currently active.
     * Inactive keys cannot be used for authentication.
     */
    @Column(name = "is_active", nullable = false)
    @NotNull
    var isActive: Boolean = true,

    /**
     * Optional notes about the API key (e.g., purpose, restrictions).
     */
    @Column(name = "notes", length = 500)
    @Size(max = 500)
    val notes: String? = null,

    /**
     * Whether this API key can delegate to users via X-MCP-User-Email header.
     * When enabled, the key acts as a trusted proxy for user authentication.
     * Feature: 050-mcp-user-delegation
     */
    @Column(name = "delegation_enabled", nullable = false)
    val delegationEnabled: Boolean = false,

    /**
     * Comma-separated list of allowed email domains for delegation.
     * Required when delegationEnabled is true (e.g., "@company.com,@subsidiary.com").
     * Domain restrictions prevent unauthorized user impersonation.
     * Feature: 050-mcp-user-delegation
     */
    @Column(name = "allowed_delegation_domains", length = 500)
    @Size(max = 500)
    val allowedDelegationDomains: String? = null
) {
    /**
     * Parse the permissions string into a set of McpPermission enums.
     */
    fun getPermissionSet(): Set<McpPermission> {
        return if (permissions.isBlank()) {
            emptySet()
        } else {
            permissions.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { McpPermission.valueOf(it) }
                .toSet()
        }
    }

    /**
     * Check if this API key has a specific permission.
     */
    fun hasPermission(permission: McpPermission): Boolean {
        return getPermissionSet().contains(permission)
    }

    /**
     * Check if the API key is currently valid (active and not expired).
     */
    fun isValid(): Boolean {
        if (!isActive) return false

        val now = LocalDateTime.now()
        return expiresAt?.isAfter(now) ?: true
    }

    /**
     * Check if the API key is expired.
     */
    fun isExpired(): Boolean {
        val now = LocalDateTime.now()
        return expiresAt?.isBefore(now) ?: false
    }

    /**
     * Update the last used timestamp to now.
     */
    fun markAsUsed() {
        lastUsedAt = LocalDateTime.now()
    }

    /**
     * Get a display-friendly representation of permissions.
     */
    fun getPermissionDisplayNames(): List<String> {
        return getPermissionSet().map { it.getDisplayName() }
    }

    /**
     * Check if this key requires admin privileges.
     */
    fun requiresAdminPrivileges(): Boolean {
        return getPermissionSet().any { it.requiresAdmin() }
    }

    /**
     * Get the list of allowed delegation domains.
     * Returns empty list if delegation is disabled or no domains are configured.
     * Feature: 050-mcp-user-delegation
     */
    fun getDelegationDomainsList(): List<String> {
        if (!delegationEnabled || allowedDelegationDomains.isNullOrBlank()) {
            return emptyList()
        }
        return allowedDelegationDomains
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it.startsWith("@") }
    }

    /**
     * Check if delegation is allowed for a given email address.
     * Validates that the email domain matches one of the allowed domains.
     * Feature: 050-mcp-user-delegation
     *
     * @param email The email address to check
     * @return true if delegation is allowed for this email, false otherwise
     */
    fun isDelegationAllowedForEmail(email: String): Boolean {
        if (!delegationEnabled || allowedDelegationDomains.isNullOrBlank()) {
            return false
        }

        val emailDomain = "@" + email.substringAfter("@").lowercase()
        val allowedDomains = getDelegationDomainsList()

        // Case-insensitive suffix match
        return allowedDomains.any { allowedDomain ->
            emailDomain.endsWith(allowedDomain)
        }
    }

    companion object {
        /**
         * Create permissions string from a set of McpPermission enums.
         */
        fun permissionsToString(permissions: Set<McpPermission>): String {
            return permissions.map { it.name }.sorted().joinToString(",")
        }

        /**
         * Validate that a permissions string contains only valid enum values.
         */
        fun validatePermissionsString(permissions: String): Boolean {
            return try {
                if (permissions.isBlank()) return false
                permissions.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { McpPermission.valueOf(it) }
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        /**
         * Generate a cryptographically secure key ID.
         */
        fun generateKeyId(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return (1..32)
                .map { chars.random() }
                .joinToString("")
        }
    }

    override fun toString(): String {
        return "McpApiKey(id=$id, keyId='$keyId', name='$name', userId=$userId, " +
               "permissionCount=${getPermissionSet().size}, isActive=$isActive, " +
               "expiresAt=$expiresAt, isExpired=${isExpired()}, " +
               "delegationEnabled=$delegationEnabled, domains=${getDelegationDomainsList().size})"
    }
}