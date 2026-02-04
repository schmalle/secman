package com.secman.service.mcp

import com.secman.controller.DelegationContext
import com.secman.domain.McpApiKey
import com.secman.domain.McpPermission
import com.secman.domain.User
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import io.micronaut.cache.annotation.Cacheable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Service for building MCP execution context with pre-computed access control data.
 *
 * Feature: 052-mcp-access-control
 *
 * Implements row-level access control for MCP tools based on User Delegation.
 * When delegation is enabled, computes accessible asset IDs using the Unified Access Control
 * policy defined in CLAUDE.md.
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
@Singleton
open class McpAccessControlService(
    private val assetRepository: AssetRepository,
    private val userMappingRepository: UserMappingRepository,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(McpAccessControlService::class.java)

    /**
     * Build execution context for MCP tool execution.
     *
     * @param apiKey The authenticated MCP API key
     * @param delegation The delegation context (null if no delegation)
     * @return McpExecutionContext with pre-computed access control data
     */
    fun buildExecutionContext(
        apiKey: McpApiKey,
        delegation: DelegationContext?
    ): McpExecutionContext {
        logger.debug(
            "Building execution context: apiKeyId={}, delegation={}",
            apiKey.id, delegation != null
        )

        return if (delegation != null) {
            buildDelegatedContext(apiKey, delegation)
        } else {
            buildApiKeyContext(apiKey)
        }
    }

    /**
     * Build context for non-delegated API key (trusted service account).
     * No access control filtering will be applied - returns all data.
     */
    private fun buildApiKeyContext(apiKey: McpApiKey): McpExecutionContext {
        logger.debug("Building API key context (no delegation): {}", apiKey.keyId)

        return McpExecutionContext.forApiKey(
            apiKeyId = apiKey.id,
            apiKeyName = apiKey.name,
            permissions = apiKey.getPermissionSet()
        )
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
        val userRoles = user.roles.map { it.name }.toSet()

        logger.debug(
            "Building delegated context: email={}, isAdmin={}, roles={}",
            delegation.delegatedUserEmail, isAdmin, userRoles
        )

        // For ADMIN users, no need to compute accessible assets
        val accessibleAssetIds = if (isAdmin) {
            null
        } else {
            getAccessibleAssetIds(delegation.delegatedUserId, delegation.delegatedUserEmail)
        }

        // Compute accessible workgroup IDs for potential workgroup-specific queries
        val accessibleWorkgroupIds = if (isAdmin) {
            null
        } else {
            user.workgroups.mapNotNull { it.id }.toSet()
        }

        return McpExecutionContext.forDelegatedUser(
            apiKeyId = apiKey.id,
            apiKeyName = apiKey.name,
            delegatedUserId = delegation.delegatedUserId,
            delegatedUserEmail = delegation.delegatedUserEmail,
            delegatedUserRoles = userRoles,
            effectivePermissions = delegation.effectivePermissions,
            isAdmin = isAdmin,
            accessibleAssetIds = accessibleAssetIds,
            accessibleWorkgroupIds = accessibleWorkgroupIds
        )
    }

    /**
     * Compute accessible asset IDs for a user using Unified Access Control rules.
     *
     * Implements caching with 5-minute TTL for performance.
     * Cache key: userId + userEmail (email used for UserMapping lookups)
     *
     * Access is granted if ANY of these criteria is met:
     * 1. Asset in user's workgroup
     * 2. Asset manually created by user
     * 3. Asset discovered via user's scan upload
     * 4. Asset's cloudAccountId matches user's AWS mappings
     * 5. Asset's adDomain matches user's domain mappings (case-insensitive)
     *
     * Note: ADMIN check happens before this method is called.
     *
     * @param userId The user's ID
     * @param userEmail The user's email (for UserMapping lookups)
     * @return Set of accessible asset IDs
     */
    @Cacheable(value = ["mcp_accessible_assets"], parameters = ["userId", "userEmail"])
    open fun getAccessibleAssetIds(userId: Long, userEmail: String): Set<Long> {
        logger.debug("Computing accessible asset IDs (cache miss): userId={}, email={}", userId, userEmail)

        // Criteria 1-3: Workgroup membership + manual creator + scan uploader
        val workgroupAssets = assetRepository
            .findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(
                userId = userId,
                manualCreatorId = userId,
                scanUploaderId = userId
            )
            .mapNotNull { it.id }

        // Criteria 4: AWS account mapping
        val awsAccountIds = userMappingRepository.findDistinctAwsAccountIdByEmail(userEmail)
        val awsAssets = if (awsAccountIds.isNotEmpty()) {
            assetRepository.findByCloudAccountIdIn(awsAccountIds).mapNotNull { it.id }
        } else {
            emptyList()
        }

        // Criteria 5: AD domain mapping (case-insensitive)
        val userDomains = userMappingRepository.findDistinctDomainByEmail(userEmail)
        val domainAssets = if (userDomains.isNotEmpty()) {
            val userDomainsLowercase = userDomains.map { it.lowercase() }
            assetRepository.findByAdDomainInIgnoreCase(userDomainsLowercase).mapNotNull { it.id }
        } else {
            emptyList()
        }

        val allAccessibleIds = (workgroupAssets + awsAssets + domainAssets).toSet()

        logger.info(
            "Computed accessible assets: userId={}, email={}, workgroup={}, aws={}, domain={}, total={}",
            userId, userEmail, workgroupAssets.size, awsAssets.size, domainAssets.size, allAccessibleIds.size
        )

        return allAccessibleIds
    }
}
