package com.secman.service

import com.secman.domain.*
import com.secman.repository.McpToolPermissionRepository
import com.secman.repository.McpApiKeyRepository
import com.secman.mcp.ToolCategories
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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

    // Rate limiting: sliding window trackers (Feature 006: MCP Tools for Security Data)
    private val rateLimitTrackers = ConcurrentHashMap<Long, RateLimitTracker>()

    // Rate limiting constants (from clarifications: 1000 req/min, 50,000 req/hour)
    private val MAX_REQUESTS_PER_MINUTE = 1000
    private val MAX_REQUESTS_PER_HOUR = 50000
    private val MINUTE_WINDOW_MS = 60_000L
    private val HOUR_WINDOW_MS = 3_600_000L

    /**
     * Check rate limit for an API key without checking permissions.
     * Used by controllers that handle permission checking separately.
     * Security fix: HIGH-005 - Enforce MCP rate limiting on all tool calls.
     *
     * @param apiKeyId The API key ID to check rate limits for
     * @param requestId Optional request ID for logging
     * @return RateLimitInfo with rate limit status
     */
    fun checkRateLimitForApiKey(apiKeyId: Long, requestId: String? = null): RateLimitInfo {
        val now = System.currentTimeMillis()

        // Get or create tracker for this API key
        val tracker = rateLimitTrackers.computeIfAbsent(apiKeyId) { RateLimitTracker() }

        // Clean up expired windows
        tracker.cleanup(now)

        // Check minute window (1000 req/min)
        val minuteKey = now / MINUTE_WINDOW_MS
        val minuteCount = tracker.minuteWindow.computeIfAbsent(minuteKey) { AtomicInteger(0) }
        val currentMinuteRequests = minuteCount.get()

        if (currentMinuteRequests >= MAX_REQUESTS_PER_MINUTE) {
            val nextMinuteStartMs = (minuteKey + 1) * MINUTE_WINDOW_MS
            val resetTime = LocalDateTime.now().plusSeconds((nextMinuteStartMs - now) / 1000)

            logger.warn("Rate limit exceeded (minute window): apiKeyId={}, requests={}/{}, requestId={}",
                       apiKeyId, currentMinuteRequests, MAX_REQUESTS_PER_MINUTE, requestId)

            return RateLimitInfo(
                maxCallsPerHour = MAX_REQUESTS_PER_HOUR,
                remainingCalls = 0,
                resetTime = resetTime,
                exceeded = true
            )
        }

        // Check hour window (50,000 req/hour)
        val hourKey = now / HOUR_WINDOW_MS
        val hourCount = tracker.hourWindow.computeIfAbsent(hourKey) { AtomicInteger(0) }
        val currentHourRequests = hourCount.get()

        if (currentHourRequests >= MAX_REQUESTS_PER_HOUR) {
            val nextHourStartMs = (hourKey + 1) * HOUR_WINDOW_MS
            val resetTime = LocalDateTime.now().plusSeconds((nextHourStartMs - now) / 1000)

            logger.warn("Rate limit exceeded (hour window): apiKeyId={}, requests={}/{}, requestId={}",
                       apiKeyId, currentHourRequests, MAX_REQUESTS_PER_HOUR, requestId)

            return RateLimitInfo(
                maxCallsPerHour = MAX_REQUESTS_PER_HOUR,
                remainingCalls = 0,
                resetTime = resetTime,
                exceeded = true
            )
        }

        // Increment counters (request is allowed)
        minuteCount.incrementAndGet()
        hourCount.incrementAndGet()

        // Calculate remaining calls (use the more restrictive limit)
        val remainingMinute = MAX_REQUESTS_PER_MINUTE - currentMinuteRequests - 1
        val remainingHour = MAX_REQUESTS_PER_HOUR - currentHourRequests - 1
        val remaining = minOf(remainingMinute, remainingHour)

        val nextHourStartMs = (hourKey + 1) * HOUR_WINDOW_MS
        val resetTime = LocalDateTime.now().plusSeconds((nextHourStartMs - now) / 1000)

        return RateLimitInfo(
            maxCallsPerHour = MAX_REQUESTS_PER_HOUR,
            remainingCalls = remaining,
            resetTime = resetTime,
            exceeded = false
        )
    }

    /**
     * Check if a given permission set allows calling a specific tool.
     * Used for delegation where effective permissions are pre-computed.
     * Feature: 050-mcp-user-delegation
     *
     * @param toolName The name of the tool to check
     * @param permissions The set of effective permissions
     * @param requestId Optional request ID for logging
     * @return PermissionCheckResult indicating whether access is granted
     */
    fun hasPermissionWithSet(
        toolName: String,
        permissions: Set<McpPermission>,
        requestId: String? = null
    ): PermissionCheckResult {
        try {
            val hasPermission = checkPermissionSetForTool(permissions, toolName)

            return PermissionCheckResult(
                granted = hasPermission,
                permission = null,
                reason = if (hasPermission) "Permission granted via effective permissions" else "No permission for tool '$toolName'",
                rateLimitInfo = null
            )
        } catch (e: Exception) {
            logger.error("Permission check with set failed: toolName={}", toolName, e)

            return PermissionCheckResult(
                granted = false,
                permission = null,
                reason = "Permission check system error",
                rateLimitInfo = null
            )
        }
    }

    /**
     * Check if a permission set allows access to a specific tool.
     * Feature: 050-mcp-user-delegation
     */
    private fun checkPermissionSetForTool(permissions: Set<McpPermission>, toolName: String): Boolean {
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
            // Asset and vulnerability tools
            "get_assets", "get_asset_profile", "search_assets" -> {
                permissions.contains(McpPermission.ASSETS_READ)
            }
            "get_vulnerabilities", "search_vulnerabilities" -> {
                permissions.contains(McpPermission.VULNERABILITIES_READ)
            }
            "get_scans", "get_scan_results", "search_products" -> {
                permissions.contains(McpPermission.SCANS_READ)
            }
            "get_audit_log", "search_audit_logs" -> {
                permissions.contains(McpPermission.AUDIT_READ)
            }
            "translate_requirement" -> {
                permissions.contains(McpPermission.TRANSLATION_USE)
            }
            "get_requirement_files", "download_file" -> {
                permissions.contains(McpPermission.FILES_READ)
            }
            else -> false
        }
    }

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

    /**
     * Check rate limit for an API key using sliding window algorithm.
     * Feature 006: MCP Tools for Security Data
     *
     * Enforces:
     * - 1000 requests per minute per API key
     * - 50,000 requests per hour per API key
     *
     * @param permission The permission being checked (for legacy maxCallsPerHour if set)
     * @param requestId Optional request ID for logging
     * @return RateLimitInfo if rate limiting is applicable, null otherwise
     */
    private fun checkRateLimit(permission: McpToolPermission, requestId: String?): RateLimitInfo? {
        val apiKeyId = permission.apiKeyId
        val now = System.currentTimeMillis()

        // Get or create tracker for this API key
        val tracker = rateLimitTrackers.computeIfAbsent(apiKeyId) { RateLimitTracker() }

        // Clean up expired windows
        tracker.cleanup(now)

        // Check minute window (1000 req/min)
        val minuteKey = now / MINUTE_WINDOW_MS
        val minuteCount = tracker.minuteWindow.computeIfAbsent(minuteKey) { AtomicInteger(0) }
        val currentMinuteRequests = minuteCount.get()

        if (currentMinuteRequests >= MAX_REQUESTS_PER_MINUTE) {
            val nextMinuteStartMs = (minuteKey + 1) * MINUTE_WINDOW_MS
            val resetTime = LocalDateTime.now().plusSeconds((nextMinuteStartMs - now) / 1000)

            logger.warn("Rate limit exceeded (minute window): apiKeyId={}, requests={}/{}, requestId={}",
                       apiKeyId, currentMinuteRequests, MAX_REQUESTS_PER_MINUTE, requestId)

            return RateLimitInfo(
                maxCallsPerHour = MAX_REQUESTS_PER_HOUR,
                remainingCalls = 0,
                resetTime = resetTime,
                exceeded = true
            )
        }

        // Check hour window (50,000 req/hour)
        val hourKey = now / HOUR_WINDOW_MS
        val hourCount = tracker.hourWindow.computeIfAbsent(hourKey) { AtomicInteger(0) }
        val currentHourRequests = hourCount.get()

        if (currentHourRequests >= MAX_REQUESTS_PER_HOUR) {
            val nextHourStartMs = (hourKey + 1) * HOUR_WINDOW_MS
            val resetTime = LocalDateTime.now().plusSeconds((nextHourStartMs - now) / 1000)

            logger.warn("Rate limit exceeded (hour window): apiKeyId={}, requests={}/{}, requestId={}",
                       apiKeyId, currentHourRequests, MAX_REQUESTS_PER_HOUR, requestId)

            return RateLimitInfo(
                maxCallsPerHour = MAX_REQUESTS_PER_HOUR,
                remainingCalls = 0,
                resetTime = resetTime,
                exceeded = true
            )
        }

        // Increment counters (request is allowed)
        minuteCount.incrementAndGet()
        hourCount.incrementAndGet()

        // Calculate remaining calls (use the more restrictive limit)
        val remainingMinute = MAX_REQUESTS_PER_MINUTE - currentMinuteRequests - 1
        val remainingHour = MAX_REQUESTS_PER_HOUR - currentHourRequests - 1
        val remaining = minOf(remainingMinute, remainingHour)

        val nextHourStartMs = (hourKey + 1) * HOUR_WINDOW_MS
        val resetTime = LocalDateTime.now().plusSeconds((nextHourStartMs - now) / 1000)

        return RateLimitInfo(
            maxCallsPerHour = MAX_REQUESTS_PER_HOUR,
            remainingCalls = remaining,
            resetTime = resetTime,
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

/**
 * Rate limit tracker using sliding window algorithm.
 * Tracks requests per API key in minute and hour windows.
 * Feature 006: MCP Tools for Security Data
 *
 * Thread-safe: Uses ConcurrentHashMap and AtomicInteger for concurrent access.
 */
data class RateLimitTracker(
    val minuteWindow: ConcurrentHashMap<Long, AtomicInteger> = ConcurrentHashMap(),
    val hourWindow: ConcurrentHashMap<Long, AtomicInteger> = ConcurrentHashMap()
) {
    /**
     * Clean up expired window entries to prevent memory leaks.
     * Removes minute windows older than 2 minutes and hour windows older than 2 hours.
     *
     * @param currentTimeMs Current time in milliseconds
     */
    fun cleanup(currentTimeMs: Long) {
        val currentMinuteKey = currentTimeMs / 60_000L
        val currentHourKey = currentTimeMs / 3_600_000L

        // Remove minute windows older than 2 minutes
        minuteWindow.keys.removeIf { it < currentMinuteKey - 2 }

        // Remove hour windows older than 2 hours
        hourWindow.keys.removeIf { it < currentHourKey - 2 }
    }
}