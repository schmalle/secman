package com.secman.service

import com.secman.domain.*
import com.secman.repository.McpApiKeyRepository
import com.secman.repository.McpAuditLogRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.LocalDateTime
import java.security.MessageDigest
import java.security.SecureRandom
import org.slf4j.LoggerFactory

/**
 * Service for MCP API key authentication and security management.
 *
 * Handles API key validation, secure hashing, rate limiting, and security monitoring
 * for MCP protocol authentication. Provides comprehensive audit logging and
 * protection against common attack vectors.
 */
@Singleton
class McpAuthenticationService(
    @Inject private val apiKeyRepository: McpApiKeyRepository,
    @Inject private val auditLogRepository: McpAuditLogRepository
) {
    private val logger = LoggerFactory.getLogger(McpAuthenticationService::class.java)
    private val secureRandom = SecureRandom()

    // Security configuration
    private val maxFailuresPerIp = 10
    private val maxFailuresPerHour = 50
    private val lockoutDurationMinutes = 30
    private val keyValidityCheckIntervalMinutes = 5

    /**
     * Authenticate a full API key string (convenience method).
     * Parses the API key and delegates to the full authentication method.
     */
    fun authenticateApiKey(
        fullApiKey: String,
        clientIp: String? = null,
        userAgent: String? = null,
        requestId: String? = null
    ): AuthenticationResult {
        // Parse the API key format: "keyId:keySecret" or just use the full key as keyId
        return if (fullApiKey.contains(":")) {
            val parts = fullApiKey.split(":", limit = 2)
            authenticateApiKey(parts[0], parts[1], clientIp, userAgent, requestId)
        } else {
            // For now, treat the full key as the keyId and use a placeholder secret
            // In a real implementation, you might need a different parsing strategy
            authenticateApiKey(fullApiKey, fullApiKey, clientIp, userAgent, requestId)
        }
    }

    /**
     * Authenticate an API key and return the associated key information.
     * Performs comprehensive validation including expiration, rate limiting, and security checks.
     */
    fun authenticateApiKey(
        keyId: String,
        keySecret: String,
        clientIp: String? = null,
        userAgent: String? = null,
        requestId: String? = null
    ): AuthenticationResult {
        val startTime = System.currentTimeMillis()

        try {
            // Input validation
            if (keyId.isBlank() || keySecret.isBlank()) {
                return createFailureResult(
                    "INVALID_CREDENTIALS",
                    "API key ID or secret is empty",
                    clientIp, userAgent, requestId
                )
            }

            // Check for brute force attacks from this IP
            if (clientIp != null && isIpBlocked(clientIp)) {
                return createFailureResult(
                    "IP_BLOCKED",
                    "Too many failed authentication attempts from this IP",
                    clientIp, userAgent, requestId
                )
            }

            // Find API key by key ID
            val apiKeyOpt = apiKeyRepository.findByKeyIdAndActive(keyId)
            if (apiKeyOpt.isEmpty) {
                logAuthFailure(null, "KEY_NOT_FOUND", "API key not found", clientIp, userAgent, requestId)
                return createFailureResult(
                    "INVALID_CREDENTIALS",
                    "Invalid API key",
                    clientIp, userAgent, requestId
                )
            }

            val apiKey = apiKeyOpt.get()

            // Verify key hash
            val hashedSecret = hashApiKeySecret(keySecret, keyId)
            if (apiKey.keyHash != hashedSecret) {
                logAuthFailure(apiKey.id, "HASH_MISMATCH", "API key secret does not match", clientIp, userAgent, requestId)
                return createFailureResult(
                    "INVALID_CREDENTIALS",
                    "Invalid API key",
                    clientIp, userAgent, requestId
                )
            }

            // Check if key is expired
            if (apiKey.isExpired()) {
                logAuthFailure(apiKey.id, "KEY_EXPIRED", "API key has expired", clientIp, userAgent, requestId)
                return createFailureResult(
                    "KEY_EXPIRED",
                    "API key has expired",
                    clientIp, userAgent, requestId
                )
            }

            // Update last used timestamp
            apiKeyRepository.updateLastUsedAt(apiKey.id, LocalDateTime.now())

            // Log successful authentication
            logAuthSuccess(apiKey.id, apiKey.userId, null, clientIp, userAgent, requestId)

            val duration = System.currentTimeMillis() - startTime
            logger.debug("API key authentication successful for keyId={} in {}ms", keyId, duration)

            return AuthenticationResult(
                success = true,
                apiKey = apiKey,
                errorCode = null,
                errorMessage = null
            )

        } catch (e: Exception) {
            logger.error("Authentication error for keyId=$keyId", e)
            logAuthFailure(null, "SYSTEM_ERROR", "Authentication system error", clientIp, userAgent, requestId)

            return createFailureResult(
                "SYSTEM_ERROR",
                "Authentication system temporarily unavailable",
                clientIp, userAgent, requestId
            )
        }
    }

    /**
     * Generate a new API key pair (ID and secret) with secure random generation.
     */
    fun generateApiKeyPair(): ApiKeyPair {
        val keyId = generateSecureKeyId()
        val keySecret = generateSecureKeySecret()
        val keyHash = hashApiKeySecret(keySecret, keyId)

        return ApiKeyPair(
            keyId = keyId,
            keySecret = keySecret,
            keyHash = keyHash
        )
    }

    /**
     * Hash an API key secret using secure hashing algorithm.
     * Uses salt derived from key ID for additional security.
     */
    fun hashApiKeySecret(secret: String, keyId: String): String {
        val salt = keyId.toByteArray(Charsets.UTF_8)
        val secretBytes = secret.toByteArray(Charsets.UTF_8)

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(secretBytes)

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Validate API key format without database lookup.
     * Performs basic format validation for security.
     */
    fun validateApiKeyFormat(keyId: String, keySecret: String): Boolean {
        // Key ID validation
        if (!keyId.matches(Regex("^[A-Za-z0-9]{16,64}$"))) {
            return false
        }

        // Key secret validation
        if (!keySecret.startsWith("sk-") || keySecret.length < 32) {
            return false
        }

        return true
    }

    /**
     * Check if an IP address is currently blocked due to failed authentication attempts.
     */
    fun isIpBlocked(clientIp: String): Boolean {
        val cutoffTime = LocalDateTime.now().minusMinutes(lockoutDurationMinutes.toLong())
        val failures = auditLogRepository.countAuthFailuresByIp(clientIp, cutoffTime)
        return failures >= maxFailuresPerIp
    }

    /**
     * Get authentication statistics for monitoring and security analysis.
     */
    fun getAuthenticationStats(since: LocalDateTime): AuthenticationStats {
        val totalAttempts = auditLogRepository.countByEventType(McpEventType.AUTH_SUCCESS, since, LocalDateTime.now()) +
                           auditLogRepository.countByEventType(McpEventType.AUTH_FAILURE, since, LocalDateTime.now())

        val successfulAttempts = auditLogRepository.countByEventType(McpEventType.AUTH_SUCCESS, since, LocalDateTime.now())
        val failedAttempts = auditLogRepository.countByEventType(McpEventType.AUTH_FAILURE, since, LocalDateTime.now())

        val successRate = if (totalAttempts > 0) {
            (successfulAttempts.toDouble() / totalAttempts.toDouble()) * 100.0
        } else 0.0

        return AuthenticationStats(
            totalAttempts = totalAttempts,
            successfulAttempts = successfulAttempts,
            failedAttempts = failedAttempts,
            successRate = successRate,
            period = since
        )
    }

    /**
     * Get failed authentication attempts for security monitoring.
     */
    fun getRecentAuthFailures(since: LocalDateTime): List<McpAuditLog> {
        return auditLogRepository.findByEventType(McpEventType.AUTH_FAILURE, since, LocalDateTime.now())
    }

    /**
     * Cleanup expired API keys and perform maintenance operations.
     */
    fun performMaintenance(): MaintenanceResult {
        val startTime = System.currentTimeMillis()
        var operationsCount = 0

        try {
            val now = LocalDateTime.now()

            // Deactivate expired keys
            val deactivatedCount = apiKeyRepository.deactivateExpired(now)
            operationsCount += deactivatedCount

            if (deactivatedCount > 0) {
                logger.info("Deactivated {} expired API keys during maintenance", deactivatedCount)
            }

            // Clean up old audit logs (keep last 90 days)
            val auditCutoff = now.minusDays(90)
            val deletedAuditLogs = auditLogRepository.deleteOlderThan(auditCutoff)
            operationsCount += deletedAuditLogs

            if (deletedAuditLogs > 0) {
                logger.info("Deleted {} old audit log entries during maintenance", deletedAuditLogs)
            }

            val duration = System.currentTimeMillis() - startTime
            logger.info("Authentication maintenance completed in {}ms, {} operations", duration, operationsCount)

            return MaintenanceResult(
                success = true,
                operationsPerformed = operationsCount,
                durationMs = duration,
                message = "Maintenance completed successfully"
            )

        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error("Authentication maintenance failed after {}ms", duration, e)

            return MaintenanceResult(
                success = false,
                operationsPerformed = operationsCount,
                durationMs = duration,
                message = "Maintenance failed: ${e.message}"
            )
        }
    }

    /**
     * Revoke an API key immediately.
     */
    fun revokeApiKey(keyId: String, reason: String, revokedBy: Long): Boolean {
        return try {
            // First, get the API key to retrieve its ID for logging
            val apiKeyOpt = apiKeyRepository.findByKeyIdAndActive(keyId)
            if (apiKeyOpt.isEmpty) {
                logger.warn("Attempted to revoke non-existent API key: {}", keyId)
                return false
            }

            val apiKey = apiKeyOpt.get()

            // Deactivate the key using a direct UPDATE query (most efficient approach)
            val updatedRows = apiKeyRepository.deactivateByKeyId(keyId)
            if (updatedRows == 0) {
                logger.warn("No rows updated when revoking API key: {}", keyId)
                return false
            }

            // Log the revocation using appropriate event type
            val auditLog = McpAuditLog(
                eventType = McpEventType.API_KEY_MANAGEMENT,
                apiKeyId = apiKey.id,
                userId = apiKey.userId,
                success = true,
                contextData = """{"action":"revoke","reason":"$reason","revokedBy":$revokedBy}""",
                requestId = "revocation-${System.currentTimeMillis()}",
                timestamp = LocalDateTime.now(),
                severity = AuditSeverity.WARN
            )
            auditLogRepository.save(auditLog)

            logger.info("API key revoked: keyId={}, reason={}, revokedBy={}", keyId, reason, revokedBy)
            true

        } catch (e: Exception) {
            logger.error("Failed to revoke API key: keyId={}", keyId, e)

            // Log the failure for audit purposes
            try {
                val failureLog = McpAuditLog(
                    eventType = McpEventType.API_KEY_MANAGEMENT,
                    apiKeyId = null,
                    userId = revokedBy,
                    success = false,
                    errorCode = "REVOCATION_FAILED",
                    errorMessage = "API key revocation failed: ${e.message}",
                    contextData = """{"action":"revoke","keyId":"$keyId","reason":"$reason","revokedBy":$revokedBy}""",
                    requestId = "revocation-failed-${System.currentTimeMillis()}",
                    timestamp = LocalDateTime.now()
                )
                auditLogRepository.save(failureLog)
            } catch (auditException: Exception) {
                logger.error("Failed to log revocation failure", auditException)
            }

            false
        }
    }

    // Private helper methods

    private fun generateSecureKeyId(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
    }

    private fun generateSecureKeySecret(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val secretPart = (1..48).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
        return "sk-$secretPart"
    }

    private fun createFailureResult(
        errorCode: String,
        errorMessage: String,
        clientIp: String?,
        userAgent: String?,
        requestId: String?
    ): AuthenticationResult {
        return AuthenticationResult(
            success = false,
            apiKey = null,
            errorCode = errorCode,
            errorMessage = errorMessage
        )
    }

    private fun logAuthSuccess(
        apiKeyId: Long,
        userId: Long,
        sessionId: String?,
        clientIp: String?,
        userAgent: String?,
        requestId: String?
    ) {
        try {
            val auditLog = McpAuditLog.createAuthSuccess(
                apiKeyId = apiKeyId,
                userId = userId,
                sessionId = sessionId,
                clientIp = clientIp,
                userAgent = userAgent,
                requestId = requestId
            )
            auditLogRepository.save(auditLog)
        } catch (e: Exception) {
            logger.error("Failed to log authentication success", e)
        }
    }

    private fun logAuthFailure(
        apiKeyId: Long?,
        errorCode: String,
        errorMessage: String,
        clientIp: String?,
        userAgent: String?,
        requestId: String?
    ) {
        try {
            val auditLog = McpAuditLog.createAuthFailure(
                apiKeyId = apiKeyId,
                errorCode = errorCode,
                errorMessage = errorMessage,
                clientIp = clientIp,
                userAgent = userAgent,
                requestId = requestId
            )
            auditLogRepository.save(auditLog)
        } catch (e: Exception) {
            logger.error("Failed to log authentication failure", e)
        }
    }
}

/**
 * Result of an API key authentication attempt.
 */
data class AuthenticationResult(
    val success: Boolean,
    val apiKey: McpApiKey?,
    val errorCode: String?,
    val errorMessage: String?
)

/**
 * Generated API key pair.
 */
data class ApiKeyPair(
    val keyId: String,
    val keySecret: String,
    val keyHash: String
)

/**
 * Authentication statistics for monitoring.
 */
data class AuthenticationStats(
    val totalAttempts: Long,
    val successfulAttempts: Long,
    val failedAttempts: Long,
    val successRate: Double,
    val period: LocalDateTime
)

/**
 * Result of maintenance operations.
 */
data class MaintenanceResult(
    val success: Boolean,
    val operationsPerformed: Int,
    val durationMs: Long,
    val message: String
)