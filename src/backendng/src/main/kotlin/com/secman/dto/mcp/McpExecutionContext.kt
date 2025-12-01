package com.secman.dto.mcp

import com.secman.domain.McpPermission
import io.micronaut.serde.annotation.Serdeable

/**
 * Execution context for MCP tool calls.
 *
 * Contains all information needed for a tool to:
 * 1. Know who is making the request (API key + optional delegated user)
 * 2. Check permissions
 * 3. Apply access control filtering
 *
 * Feature: 052-mcp-access-control
 * Implements row-level access control for MCP tools based on User Delegation.
 *
 * Access Control Rules (from CLAUDE.md - Unified Access Control):
 * Users can access assets if ANY of these is true:
 * 1. User has ADMIN role (universal access)
 * 2. Asset in user's workgroup
 * 3. Asset manually created by user
 * 4. Asset discovered via user's scan upload
 * 5. Asset's cloudAccountId matches user's AWS mappings (UserMapping)
 * 6. Asset's adDomain matches user's domain mappings (UserMapping, case-insensitive)
 */
@Serdeable
data class McpExecutionContext(
    // API Key information
    val apiKeyId: Long,
    val apiKeyName: String,

    // Delegation information (null if no delegation)
    val delegatedUserId: Long?,
    val delegatedUserEmail: String?,
    val delegatedUserRoles: Set<String>?,

    // Effective permissions (intersection of API key + user roles if delegated)
    val effectivePermissions: Set<McpPermission>,

    // Pre-computed access control data for efficient filtering
    // null for ADMIN = all access, empty set = no access
    val isAdmin: Boolean,
    val accessibleAssetIds: Set<Long>?,
    val accessibleWorkgroupIds: Set<Long>?
) {
    /**
     * Check if this context has user delegation enabled.
     */
    fun hasDelegation(): Boolean = delegatedUserId != null

    /**
     * Check if the context has a specific permission.
     */
    fun hasPermission(permission: McpPermission): Boolean =
        effectivePermissions.contains(permission)

    /**
     * Check if the user can access a specific asset.
     *
     * Access is granted if:
     * - User is ADMIN (isAdmin = true), OR
     * - Asset ID is in the pre-computed accessible set, OR
     * - No delegation (API key acts as service account with full access)
     */
    fun canAccessAsset(assetId: Long): Boolean {
        // ADMIN has universal access
        if (isAdmin) return true

        // No delegation = API key is trusted service account
        if (!hasDelegation()) return true

        // Check pre-computed accessible assets
        return accessibleAssetIds?.contains(assetId) == true
    }

    /**
     * Check if access control filtering should be applied.
     * Returns false for ADMIN or non-delegated requests.
     */
    fun shouldApplyAccessControl(): Boolean {
        return hasDelegation() && !isAdmin
    }

    /**
     * Get the accessible asset IDs for filtering, or null if no filtering needed.
     */
    fun getFilterableAssetIds(): Set<Long>? {
        return if (shouldApplyAccessControl()) accessibleAssetIds else null
    }

    companion object {
        /**
         * Create a context for non-delegated API key (trusted service account).
         * No access control filtering will be applied.
         */
        fun forApiKey(
            apiKeyId: Long,
            apiKeyName: String,
            permissions: Set<McpPermission>
        ): McpExecutionContext {
            return McpExecutionContext(
                apiKeyId = apiKeyId,
                apiKeyName = apiKeyName,
                delegatedUserId = null,
                delegatedUserEmail = null,
                delegatedUserRoles = null,
                effectivePermissions = permissions,
                isAdmin = false,  // API key alone doesn't grant ADMIN
                accessibleAssetIds = null,  // No filtering for service accounts
                accessibleWorkgroupIds = null
            )
        }

        /**
         * Create a context for delegated user with pre-computed access control.
         */
        fun forDelegatedUser(
            apiKeyId: Long,
            apiKeyName: String,
            delegatedUserId: Long,
            delegatedUserEmail: String,
            delegatedUserRoles: Set<String>,
            effectivePermissions: Set<McpPermission>,
            isAdmin: Boolean,
            accessibleAssetIds: Set<Long>?,
            accessibleWorkgroupIds: Set<Long>?
        ): McpExecutionContext {
            return McpExecutionContext(
                apiKeyId = apiKeyId,
                apiKeyName = apiKeyName,
                delegatedUserId = delegatedUserId,
                delegatedUserEmail = delegatedUserEmail,
                delegatedUserRoles = delegatedUserRoles,
                effectivePermissions = effectivePermissions,
                isAdmin = isAdmin,
                accessibleAssetIds = if (isAdmin) null else accessibleAssetIds,
                accessibleWorkgroupIds = if (isAdmin) null else accessibleWorkgroupIds
            )
        }
    }
}
