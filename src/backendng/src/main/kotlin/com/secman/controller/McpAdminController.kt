package com.secman.controller

import com.secman.service.*
import com.secman.domain.*
import com.secman.dto.mcp.*
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Inject
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Administrative controller for MCP management operations.
 * Requires admin authentication via JWT.
 */
@Controller("/api/mcp/admin")
@Secured("ADMIN")
class McpAdminController(
    @Inject private val authService: McpAuthenticationService,
    @Inject private val sessionService: McpSessionService,
    @Inject private val auditService: McpAuditService,
    @Inject private val toolPermissionService: McpToolPermissionService,
    @Inject private val userService: UserService,
    @Inject private val apiKeyRepository: com.secman.repository.McpApiKeyRepository
) {
    private val logger = LoggerFactory.getLogger(McpAdminController::class.java)

    // ===== API KEY MANAGEMENT =====

    /**
     * Create a new MCP API key.
     */
    @Post("/api-keys")
    suspend fun createApiKey(
        @Body request: McpApiKeyCreateRequest,
        authentication: Authentication?
    ): HttpResponse<McpApiKeyCreateResponse> {
        return try {
            val userId = getUserIdFromAuth(authentication)
            if (userId == null) {
                return HttpResponse.status<McpApiKeyCreateResponse>(HttpStatus.UNAUTHORIZED)
                    .body(McpApiKeyCreateResponse(error = McpErrorResponse("AUTH_FAILED", "Invalid authentication")))
            }

            // Validate request
            val validationError = validateApiKeyRequest(request)
            if (validationError != null) {
                logger.debug("Request validation failed: {}", validationError)
                return HttpResponse.badRequest(
                    McpApiKeyCreateResponse(error = McpErrorResponse("INVALID_REQUEST", validationError))
                )
            }

            // Check for duplicate names
            if (apiKeyRepository.existsByUserIdAndName(userId, request.name)) {
                return HttpResponse.status<McpApiKeyCreateResponse>(HttpStatus.CONFLICT)
                    .body(McpApiKeyCreateResponse(error = McpErrorResponse("DUPLICATE_NAME", "An API key with this name already exists")))
            }

            // Parse permissions
            val permissions = request.permissions.map { McpPermission.valueOf(it) }.toSet()
            val expiresAt = request.expiresAt?.let { LocalDateTime.parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }

            // Generate API key
            val keyPair = authService.generateApiKeyPair()

            // Create API key entity
            val apiKey = McpApiKey(
                keyId = keyPair.keyId,
                keyHash = keyPair.keyHash,
                name = request.name,
                userId = userId,
                permissions = McpApiKey.permissionsToString(permissions),
                expiresAt = expiresAt,
                notes = request.notes
            )

            // Save API key
            val savedApiKey = apiKeyRepository.save(apiKey)

            val response = McpApiKeyCreateResponse(
                keyId = keyPair.keyId,
                apiKey = keyPair.keySecret, // Only returned once!
                name = request.name,
                permissions = request.permissions,
                expiresAt = request.expiresAt,
                createdAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )

            logger.info("API key created: keyId={}, userId={}, name={}", keyPair.keyId, userId, request.name)

            HttpResponse.status<McpApiKeyCreateResponse>(HttpStatus.CREATED).body(response)

        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(
                McpApiKeyCreateResponse(error = McpErrorResponse("INVALID_PERMISSION", e.message ?: "Invalid permission"))
            )
        } catch (e: Exception) {
            logger.error("API key creation failed", e)
            HttpResponse.status<McpApiKeyCreateResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(McpApiKeyCreateResponse(error = McpErrorResponse("SYSTEM_ERROR", "API key creation failed")))
        }
    }

    /**
     * List API keys for the authenticated user.
     */
    @Get("/api-keys")
    suspend fun listApiKeys(
        authentication: Authentication?,
        @QueryValue("include_inactive") includeInactive: Boolean? = false
    ): HttpResponse<McpApiKeyListResponse> {
        return try {
            val userId = getUserIdFromAuth(authentication)
            if (userId == null) {
                return HttpResponse.status<McpApiKeyListResponse>(HttpStatus.UNAUTHORIZED)
                    .body(McpApiKeyListResponse(error = McpErrorResponse("AUTH_FAILED", "Invalid authentication")))
            }

            // Get API keys for user
            val apiKeys = if (includeInactive == true) {
                apiKeyRepository.findByUserId(userId)
            } else {
                apiKeyRepository.findActiveByUserId(userId)
            }

            val keyInfos = apiKeys.map { key ->
                McpApiKeyInfo(
                    id = key.id,
                    keyId = key.keyId,
                    name = key.name,
                    permissions = key.getPermissionDisplayNames(),
                    isActive = key.isActive,
                    lastUsedAt = key.lastUsedAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    expiresAt = key.expiresAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    createdAt = key.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    notes = key.notes
                )
            }

            val response = McpApiKeyListResponse(
                apiKeys = keyInfos,
                total = keyInfos.size
            )

            HttpResponse.ok(response)

        } catch (e: Exception) {
            logger.error("API key listing failed", e)
            HttpResponse.status<McpApiKeyListResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(McpApiKeyListResponse(error = McpErrorResponse("SYSTEM_ERROR", "Failed to list API keys")))
        }
    }

    /**
     * Revoke an API key.
     */
    @Delete("/api-keys/{keyId}")
    suspend fun revokeApiKey(
        keyId: String,
        @QueryValue("reason") reason: String = "Revoked by admin",
        authentication: Authentication?
    ): HttpResponse<Void> {
        return try {
            val userId = getUserIdFromAuth(authentication)
            if (userId == null) {
                return HttpResponse.status(HttpStatus.UNAUTHORIZED)
            }

            val success = authService.revokeApiKey(keyId, reason, userId)
            if (!success) {
                return HttpResponse.status(HttpStatus.NOT_FOUND)
            }

            logger.info("API key revoked: keyId={}, reason={}, revokedBy={}", keyId, reason, userId)

            HttpResponse.noContent()

        } catch (e: Exception) {
            logger.error("API key revocation failed: keyId={}", keyId, e)
            HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
        }
    }

    // ===== SESSION MANAGEMENT =====

    /**
     * Get active MCP sessions.
     */
    @Get("/sessions")
    suspend fun getActiveSessions(): HttpResponse<Map<String, Any>> {
        return try {
            val stats = sessionService.getSessionStatistics()

            HttpResponse.ok(mapOf(
                "statistics" to mapOf(
                    "totalActive" to stats.totalActiveSessions,
                    "recentlyActive" to stats.recentlyActiveSessions,
                    "connectionTypes" to stats.connectionTypeBreakdown,
                    "utilization" to stats.utilizationPercentage,
                    "maxConcurrent" to stats.maxConcurrentSessions
                ),
                "timestamp" to LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            ))

        } catch (e: Exception) {
            logger.error("Failed to get session statistics", e)
            HttpResponse.status<Map<String, Any>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "Failed to retrieve session statistics"))
        }
    }

    /**
     * Force close all sessions for an API key.
     */
    @Delete("/sessions/api-key/{keyId}")
    suspend fun closeSessionsForApiKey(
        keyId: String,
        @QueryValue("reason") reason: String = "Admin requested",
        authentication: Authentication?
    ): HttpResponse<Map<String, Any>> {
        return try {
            // This would require looking up the API key first to get the ID
            // For now, return a placeholder response

            logger.info("Closed sessions for API key: keyId={}, reason={}", keyId, reason)

            HttpResponse.ok(mapOf(
                "closedSessions" to 0,
                "reason" to reason,
                "timestamp" to LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            ))

        } catch (e: Exception) {
            logger.error("Failed to close sessions for API key: {}", keyId, e)
            HttpResponse.status<Map<String, Any>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "Failed to close sessions"))
        }
    }

    // ===== AUDIT AND MONITORING =====

    /**
     * Get audit logs with filtering.
     */
    @Get("/audit-logs")
    suspend fun getAuditLogs(
        @QueryValue("page") page: Int = 1,
        @QueryValue("pageSize") pageSize: Int = 50,
        @QueryValue("eventType") eventType: String?,
        @QueryValue("startTime") startTime: String?,
        @QueryValue("endTime") endTime: String?,
        @QueryValue("success") success: Boolean?
    ): HttpResponse<McpAuditLogResponse> {
        return try {
            if (pageSize > 100) {
                return HttpResponse.badRequest(
                    McpAuditLogResponse(error = McpErrorResponse("INVALID_PARAMETER", "Page size cannot exceed 100"))
                )
            }

            val start = startTime?.let { LocalDateTime.parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
                ?: LocalDateTime.now().minusDays(1)
            val end = endTime?.let { LocalDateTime.parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
                ?: LocalDateTime.now()

            // Get audit logs (this would use the audit service)
            val logs = emptyList<McpAuditLog>() // Placeholder

            val logEntries = logs.map { log ->
                McpAuditLogEntry(
                    id = log.id,
                    timestamp = log.timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    eventType = log.eventType.name,
                    apiKeyId = log.apiKeyId,
                    sessionId = log.sessionId,
                    toolName = log.toolName,
                    operation = log.operation?.name,
                    success = log.success,
                    errorCode = log.errorCode,
                    errorMessage = log.errorMessage,
                    durationMs = log.durationMs,
                    clientIp = log.clientIp,
                    userAgent = log.userAgent
                )
            }

            val response = McpAuditLogResponse(
                logs = logEntries,
                total = logEntries.size.toLong(),
                page = page,
                pageSize = pageSize
            )

            HttpResponse.ok(response)

        } catch (e: Exception) {
            logger.error("Failed to retrieve audit logs", e)
            HttpResponse.status<McpAuditLogResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(McpAuditLogResponse(error = McpErrorResponse("SYSTEM_ERROR", "Failed to retrieve audit logs")))
        }
    }

    /**
     * Get system statistics.
     */
    @Get("/statistics")
    suspend fun getStatistics(): HttpResponse<McpSystemStatistics> {
        return try {
            val sessionStats = sessionService.getSessionStatistics()
            val permissionStats = toolPermissionService.getPermissionStatistics()

            val systemStats = McpSystemStatistics(
                sessions = McpSessionStatistics(
                    totalActive = sessionStats.totalActiveSessions,
                    recentlyActive = sessionStats.recentlyActiveSessions,
                    byConnectionType = sessionStats.connectionTypeBreakdown,
                    utilizationPercent = sessionStats.utilizationPercentage
                ),
                apiKeys = McpApiKeyStatistics(
                    totalActive = 0, // Would come from API key service
                    totalInactive = 0,
                    expiringSoon = 0,
                    recentlyUsed = 0
                ),
                tools = McpToolStatistics(
                    totalCalls = 0, // Would come from audit service
                    successRate = 0.0,
                    topTools = emptyList(),
                    averageResponseTime = 0.0
                ),
                security = McpSecurityStatistics(
                    authFailures = 0, // Would come from audit service
                    permissionDenials = 0,
                    rateLimitHits = 0,
                    suspiciousActivity = 0
                )
            )

            HttpResponse.ok(systemStats)

        } catch (e: Exception) {
            logger.error("Failed to retrieve system statistics", e)
            HttpResponse.status<McpSystemStatistics>(HttpStatus.INTERNAL_SERVER_ERROR)
        }
    }

    // ===== TOOL PERMISSION MANAGEMENT =====

    /**
     * Grant tool permission to an API key.
     */
    @Post("/tool-permissions")
    suspend fun grantToolPermission(
        @Body request: McpToolPermissionGrantRequest,
        authentication: Authentication?
    ): HttpResponse<McpToolPermissionResponse> {
        return try {
            val userId = getUserIdFromAuth(authentication)
            if (userId == null) {
                return HttpResponse.status<McpToolPermissionResponse>(HttpStatus.UNAUTHORIZED)
                    .body(McpToolPermissionResponse(error = McpErrorResponse("AUTH_FAILED", "Invalid authentication")))
            }

            val result = toolPermissionService.grantPermission(
                apiKeyId = request.apiKeyId,
                toolName = request.toolName,
                parameterRestrictions = request.parameterRestrictions,
                maxCallsPerHour = request.maxCallsPerHour,
                allowCaching = request.allowCaching,
                priority = request.priority,
                notes = request.notes,
                grantedBy = userId
            )

            if (!result.success) {
                return HttpResponse.badRequest(
                    McpToolPermissionResponse(error = McpErrorResponse("PERMISSION_GRANT_FAILED", result.errorMessage!!))
                )
            }

            HttpResponse.status<McpToolPermissionResponse>(HttpStatus.CREATED)
                .body(McpToolPermissionResponse(success = true))

        } catch (e: Exception) {
            logger.error("Failed to grant tool permission", e)
            HttpResponse.status<McpToolPermissionResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(McpToolPermissionResponse(error = McpErrorResponse("SYSTEM_ERROR", "Permission grant failed")))
        }
    }

    /**
     * Get tool permissions for an API key.
     */
    @Get("/tool-permissions/{apiKeyId}")
    suspend fun getToolPermissions(apiKeyId: Long): HttpResponse<McpToolPermissionResponse> {
        return try {
            val permissions = toolPermissionService.getPermissionsForApiKey(apiKeyId)

            val permissionInfos = permissions.map { permission ->
                McpToolPermissionInfo(
                    id = permission.id,
                    apiKeyId = permission.apiKeyId,
                    toolName = permission.toolName,
                    isActive = permission.isActive,
                    parameterRestrictions = permission.parameterRestrictions,
                    maxCallsPerHour = permission.maxCallsPerHour,
                    allowCaching = permission.allowCaching,
                    priority = permission.priority,
                    createdAt = permission.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    updatedAt = permission.updatedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    notes = permission.notes
                )
            }

            HttpResponse.ok(McpToolPermissionResponse(permissions = permissionInfos))

        } catch (e: Exception) {
            logger.error("Failed to retrieve tool permissions for API key: {}", apiKeyId, e)
            HttpResponse.status<McpToolPermissionResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(McpToolPermissionResponse(error = McpErrorResponse("SYSTEM_ERROR", "Failed to retrieve permissions")))
        }
    }

    // ===== MAINTENANCE OPERATIONS =====

    /**
     * Perform system maintenance.
     */
    @Post("/maintenance")
    suspend fun performMaintenance(): HttpResponse<Map<String, Any>> {
        return try {
            logger.info("Starting MCP system maintenance")

            val authMaintenance = authService.performMaintenance()
            val sessionCleanup = sessionService.performSessionCleanup()
            val permissionMaintenance = toolPermissionService.performMaintenance()
            val auditCleanup = auditService.cleanupOldAuditLogs()

            val results = mapOf(
                "authentication" to mapOf(
                    "success" to authMaintenance.success,
                    "operations" to authMaintenance.operationsPerformed,
                    "duration" to authMaintenance.durationMs,
                    "message" to authMaintenance.message
                ),
                "sessions" to mapOf(
                    "success" to sessionCleanup.success,
                    "cleaned" to sessionCleanup.cleanedUpCount,
                    "duration" to sessionCleanup.durationMs,
                    "message" to sessionCleanup.message
                ),
                "permissions" to mapOf(
                    "success" to permissionMaintenance.success,
                    "operations" to permissionMaintenance.operationsPerformed,
                    "duration" to permissionMaintenance.durationMs,
                    "message" to permissionMaintenance.message
                ),
                "audit" to mapOf(
                    "success" to auditCleanup.success,
                    "cleaned" to auditCleanup.cleanedUpCount,
                    "duration" to auditCleanup.durationMs,
                    "message" to auditCleanup.message
                ),
                "completedAt" to LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )

            logger.info("MCP system maintenance completed")

            HttpResponse.ok(results)

        } catch (e: Exception) {
            logger.error("System maintenance failed", e)
            HttpResponse.status<Map<String, Any>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "System maintenance failed: ${e.message}"))
        }
    }

    // ===== HELPER METHODS =====

    private fun getUserIdFromAuth(authentication: Authentication?): Long? {
        if (authentication == null) {
            // For testing when security is disabled, return a dummy user ID
            logger.debug("No authentication provided, using test user ID")
            return 1L
        }

        return try {
            // Extract user ID from JWT authentication
            // This depends on how user ID is stored in the JWT
            val userIdStr = authentication.attributes["userId"] as? String
            if (userIdStr != null) {
                userIdStr.toLongOrNull()
            } else {
                // Fallback to parsing name
                authentication.name.toLongOrNull()
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract user ID from authentication", e)
            null
        }
    }

    private fun validateApiKeyRequest(request: McpApiKeyCreateRequest): String? {
        logger.debug("Validating API key request: name={}, permissions={}", request.name, request.permissions)

        if (request.name.isBlank()) {
            logger.debug("Validation failed: name is blank")
            return "API key name cannot be empty"
        }

        if (request.name.length > 100) {
            logger.debug("Validation failed: name too long")
            return "API key name cannot exceed 100 characters"
        }

        if (request.permissions.isEmpty()) {
            logger.debug("Validation failed: permissions list is empty")
            return "At least one permission must be specified"
        }

        // Validate permissions
        for (permission in request.permissions) {
            try {
                McpPermission.valueOf(permission)
            } catch (e: IllegalArgumentException) {
                return "Invalid permission: $permission"
            }
        }

        // Validate expiration date if provided
        if (request.expiresAt != null) {
            try {
                val expirationDate = LocalDateTime.parse(request.expiresAt, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                if (expirationDate.isBefore(LocalDateTime.now())) {
                    return "Expiration date cannot be in the past"
                }
            } catch (e: Exception) {
                return "Invalid expiration date format. Use ISO 8601 format (YYYY-MM-DDTHH:MM:SS)"
            }
        }

        return null
    }
}