package com.secman.service.mcp

import com.secman.controller.DelegationContext
import com.secman.domain.McpApiKey
import com.secman.domain.User
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.service.AssetFilterService
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/** Builds MCP scope from the same live policy used by REST. */
@Singleton
open class McpAccessControlService(
    private val assetRepository: AssetRepository,
    private val userRepository: UserRepository,
    private val assetFilterService: AssetFilterService
) {
    private val logger = LoggerFactory.getLogger(McpAccessControlService::class.java)

    /**
     * Build execution context for MCP tool execution.
     *
     * SECURITY: Delegation is mandatory for all data-accessing endpoints.
     * The controller layer enforces this; this null check is defense-in-depth.
     *
     * @param apiKey The authenticated MCP API key
     * @param delegation The delegation context (must not be null)
     * @return McpExecutionContext with pre-computed access control data
     * @throws IllegalStateException if delegation is null (programming error)
     */
    fun buildExecutionContext(
        apiKey: McpApiKey,
        delegation: DelegationContext
    ): McpExecutionContext {
        logger.debug(
            "Building execution context: apiKeyId={}, delegatedUser={}",
            apiKey.id, delegation.delegatedUserEmail
        )

        return buildDelegatedContext(apiKey, delegation)
    }

    /**
     * Build context for delegated user with pre-computed access control data.
     * Feature 073: Uses findByIdWithWorkgroups() for LAZY loading support.
     */
    private fun buildDelegatedContext(
        apiKey: McpApiKey,
        delegation: DelegationContext
    ): McpExecutionContext {
        // Feature 073: Use findByIdWithWorkgroups() to load workgroups with LAZY loading
        val user = userRepository.findByIdWithWorkgroups(delegation.delegatedUserId).orElse(null)
            ?: throw IllegalStateException("Delegated user not found: ${delegation.delegatedUserId}")

        val isAdmin = user.roles.contains(User.Role.ADMIN)
        val hasUniversalAssetAccess = isAdmin || user.roles.contains(User.Role.SECCHAMPION)
        val userRoles = user.roles.map { it.name }.toSet()

        logger.debug(
            "Building delegated context: email={}, isAdmin={}, roles={}",
            delegation.delegatedUserEmail, isAdmin, userRoles
        )

        // For ADMIN users, no need to compute accessible assets
        val accessibleAssetIds = when {
            isAdmin -> null
            hasUniversalAssetAccess -> assetRepository.findAllIds().toSet()
            else -> assetFilterService.getAccessibleAssetIds(Authentication.build(
                user.username, userRoles, mapOf("userId" to user.id!!, "email" to user.email)
            ))
        }

        // Compute accessible workgroup IDs for potential workgroup-specific queries
        val accessibleWorkgroupIds = if (isAdmin) {
            null
        } else {
            user.workgroups.filter { it.enabled }.mapNotNull { it.id }.toSet()
        }

        return McpExecutionContext.forDelegatedUser(
            apiKeyId = apiKey.id,
            apiKeyName = apiKey.name,
            delegatedUserId = delegation.delegatedUserId,
            delegatedUserEmail = delegation.delegatedUserEmail,
            delegatedUsername = user.username,
            delegatedUserRoles = userRoles,
            effectivePermissions = delegation.effectivePermissions,
            isAdmin = isAdmin,
            accessibleAssetIds = accessibleAssetIds,
            accessibleWorkgroupIds = accessibleWorkgroupIds
        )
    }

}
