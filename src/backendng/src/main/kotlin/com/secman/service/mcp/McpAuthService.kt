package com.secman.service.mcp

import com.secman.domain.McpApiKey
import com.secman.domain.McpPermission
import com.secman.repository.McpApiKeyRepository
import com.secman.repository.UserRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Service for MCP API key authentication and authorization.
 *
 * Handles:
 * - API key validation
 * - Permission checking
 * - Workgroup extraction for access control
 * - Usage tracking
 *
 * Feature 009: T022
 */
@Singleton
class McpAuthService {

    private val logger = LoggerFactory.getLogger(McpAuthService::class.java)

    @Inject
    lateinit var apiKeyRepository: McpApiKeyRepository

    @Inject
    lateinit var userRepository: UserRepository

    /**
     * Validate an API key by its key ID.
     * Returns the API key if valid and active, null otherwise.
     *
     * @param keyId The API key identifier
     * @return The validated API key or null if invalid/expired
     */
    fun validateApiKey(keyId: String): McpApiKey? {
        if (keyId.isBlank()) {
            logger.debug("API key validation failed: empty keyId")
            return null
        }

        val apiKey = apiKeyRepository.findByKeyIdAndActive(keyId).orElse(null)
        if (apiKey == null) {
            logger.debug("API key validation failed: keyId not found or inactive: {}", keyId)
            return null
        }

        // Check expiration
        if (apiKey.isExpired()) {
            logger.debug("API key validation failed: key expired: {}", keyId)
            return null
        }

        // Validate that key is valid (combines active check + expiration)
        if (!apiKey.isValid()) {
            logger.debug("API key validation failed: key not valid: {}", keyId)
            return null
        }

        logger.debug("API key validated successfully: {} for user {}", keyId, apiKey.userId)
        return apiKey
    }

    /**
     * Check if an API key has a specific permission.
     *
     * @param apiKey The API key to check
     * @param permission The permission to verify
     * @return True if the key has the permission
     */
    fun hasPermission(apiKey: McpApiKey, permission: McpPermission): Boolean {
        val hasPermission = apiKey.hasPermission(permission)
        logger.debug(
            "Permission check: keyId={}, permission={}, result={}",
            apiKey.keyId,
            permission,
            hasPermission
        )
        return hasPermission
    }

    /**
     * Extract workgroup IDs from the user associated with this API key.
     * Returns empty set if user has no workgroups.
     * Feature 073: Uses findByIdWithWorkgroups() for LAZY loading support.
     *
     * @param apiKey The API key
     * @return Set of workgroup IDs the user belongs to
     */
    fun extractWorkgroups(apiKey: McpApiKey): Set<Long> {
        // Feature 073: Use findByIdWithWorkgroups() to load workgroups with LAZY loading
        val user = userRepository.findByIdWithWorkgroups(apiKey.userId).orElse(null)
        if (user == null) {
            logger.warn("User not found for API key: userId={}", apiKey.userId)
            return emptySet()
        }

        val workgroupIds = user.workgroups.mapNotNull { it.id }.toSet()
        logger.debug(
            "Extracted workgroups for user {}: {}",
            apiKey.userId,
            workgroupIds
        )
        return workgroupIds
    }

    /**
     * Extract the user ID from an API key.
     *
     * @param apiKey The API key
     * @return The user ID
     */
    fun extractUserId(apiKey: McpApiKey): Long {
        return apiKey.userId
    }

    /**
     * Check if an API key is expired.
     *
     * @param apiKey The API key to check
     * @return True if the key is expired
     */
    fun isKeyExpired(apiKey: McpApiKey): Boolean {
        return apiKey.isExpired()
    }

    /**
     * Record usage of an API key by updating its lastUsedAt timestamp.
     * This is called after successful authentication to track key activity.
     *
     * @param apiKey The API key that was used
     */
    fun recordUsage(apiKey: McpApiKey) {
        try {
            val now = LocalDateTime.now()
            val rowsUpdated = apiKeyRepository.updateLastUsedAt(apiKey.id, now)
            if (rowsUpdated > 0) {
                logger.debug("Recorded usage for API key: {}", apiKey.keyId)
            } else {
                logger.warn("Failed to record usage for API key: {}", apiKey.keyId)
            }
        } catch (e: Exception) {
            logger.error("Error recording API key usage: keyId={}", apiKey.keyId, e)
            // Don't throw - usage tracking failure shouldn't break request
        }
    }

    /**
     * Validate API key and check permission in one operation.
     * Convenience method for common authentication flow.
     *
     * @param keyId The API key identifier
     * @param permission The required permission
     * @return The validated API key if valid and has permission, null otherwise
     */
    fun validateAndCheckPermission(keyId: String, permission: McpPermission): McpApiKey? {
        val apiKey = validateApiKey(keyId) ?: return null
        if (!hasPermission(apiKey, permission)) {
            logger.debug(
                "API key lacks required permission: keyId={}, permission={}",
                keyId,
                permission
            )
            return null
        }
        return apiKey
    }

    /**
     * Get full authentication context for an API key.
     * Returns a map with userId, workgroups, and permissions.
     *
     * @param apiKey The validated API key
     * @return Map containing authentication context
     */
    fun getAuthContext(apiKey: McpApiKey): Map<String, Any> {
        return mapOf(
            "userId" to apiKey.userId,
            "workgroups" to extractWorkgroups(apiKey),
            "permissions" to apiKey.getPermissionSet(),
            "keyId" to apiKey.keyId,
            "keyName" to apiKey.name
        )
    }
}
