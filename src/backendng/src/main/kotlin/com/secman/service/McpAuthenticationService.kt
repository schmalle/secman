package com.secman.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.secman.domain.*
import com.secman.repository.McpApiKeyRepository
import com.secman.repository.McpAuditLogRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.LocalDateTime
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

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
    private val bcryptEncoder = BCryptPasswordEncoder()

    // Security configuration
    private val maxFailuresPerIp = 10
    private val maxFailuresPerHour = 50
    private val lockoutDurationMinutes = 30
    private val keyValidityCheckIntervalMinutes = 5

    /**
     * Secret-only authentication used to BCrypt the presented secret against every active key
     * (~80 ms of CPU each) on every MCP call. After one successful match this remembers which key
     * row the secret belongs to, keyed by an HMAC under a per-process random key, so neither the
     * secret nor an offline-crackable hash is ever held. The key row is still read on every call,
     * and the entry is honored only while that row is active, unexpired and carries the same
     * stored hash; revocation, deletion and rotation therefore take effect immediately.
     */
    private data class VerifiedKey(val apiKeyRowId: Long, val keyHash: String)
    private val verifiedSecrets: Cache<String, VerifiedKey> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(Duration.ofHours(1))
        .build()
    private val secretFingerprintKey = SecretKeySpec(ByteArray(32).also { secureRandom.nextBytes(it) }, "HmacSHA256")

    /** `lastUsedAt` is informational; writing it on every call turned each read into a write. */
    private val lastUsedWriteInterval = Duration.ofMinutes(1)

    /**
     * Authenticate a full API key string (convenience method).
     * Handles the standard format where only the secret is provided.
     */
    fun authenticateApiKey(
        fullApiKey: String,
        clientIp: String? = null,
        userAgent: String? = null,
        requestId: String? = null
    ): AuthenticationResult {
        // For MCP, we typically only get the secret part (sk-xxxx)
        // We need to find the key by checking all stored key hashes
        return authenticateApiKeyBySecret(fullApiKey, clientIp, userAgent, requestId)
    }

    /**
     * Authenticate using only the API key secret.
     * This method searches through all active keys to find a matching hash.
     */
    fun authenticateApiKeyBySecret(
        keySecret: String,
        clientIp: String? = null,
        userAgent: String? = null,
        requestId: String? = null
    ): AuthenticationResult {
        val startTime = System.currentTimeMillis()

        try {
            // Input validation
            if (keySecret.isBlank()) {
                return createFailureResult(
                    "INVALID_CREDENTIALS",
                    "API key secret is empty",
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

            val fingerprint = fingerprint(keySecret)
            verifiedSecrets.getIfPresent(fingerprint)?.let { verified ->
                val apiKey = apiKeyRepository.findById(verified.apiKeyRowId).orElse(null)
                if (apiKey != null && apiKey.isActive && apiKey.keyHash == verified.keyHash) {
                    return validateAndReturnKey(apiKey, clientIp, userAgent, requestId, startTime)
                }
                verifiedSecrets.invalidate(fingerprint)
            }

            // Try to find an API key by checking hashes for all active keys
            val matched = getAllActiveKeys().firstOrNull { verifyApiKeySecret(keySecret, it.keyHash, it.keyId) }
            if (matched != null) {
                migrateHashIfNeeded(matched, keySecret)
                val result = validateAndReturnKey(matched, clientIp, userAgent, requestId, startTime)
                if (result.success) {
                    // Re-read so a hash migrated just above is the one remembered.
                    apiKeyRepository.findById(matched.id).ifPresent {
                        verifiedSecrets.put(fingerprint, VerifiedKey(it.id, it.keyHash))
                    }
                }
                return result
            }

            // No matching key found
            logAuthFailure(null, "KEY_NOT_FOUND", "API key not found", clientIp, userAgent, requestId)
            return createFailureResult(
                "INVALID_CREDENTIALS",
                "Invalid API key",
                clientIp, userAgent, requestId
            )

        } catch (e: Exception) {
            logger.error("Authentication error for keySecret", e)
            logAuthFailure(null, "SYSTEM_ERROR", "Authentication system error", clientIp, userAgent, requestId)

            return createFailureResult(
                "SYSTEM_ERROR",
                "Authentication system temporarily unavailable",
                clientIp, userAgent, requestId
            )
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

            // Verify key hash (supports both BCrypt and legacy SHA-256)
            if (!verifyApiKeySecret(keySecret, apiKey.keyHash, keyId)) {
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

            migrateHashIfNeeded(apiKey, keySecret)

            touchLastUsed(apiKey)

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
        val keyHash = hashApiKeySecret(keySecret)

        return ApiKeyPair(
            keyId = keyId,
            keySecret = keySecret,
            keyHash = keyHash
        )
    }

    /**
     * Hash an API key secret using BCrypt.
     */
    fun hashApiKeySecret(secret: String): String {
        return bcryptEncoder.encode(secret)!!
    }

    private fun legacyHashApiKeySecret(secret: String, keyId: String): String {
        val salt = keyId.toByteArray(Charsets.UTF_8)
        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(secretBytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyApiKeySecret(secret: String, storedHash: String, keyId: String): Boolean {
        return if (storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$")) {
            bcryptEncoder.matches(secret, storedHash)
        } else {
            legacyHashApiKeySecret(secret, keyId) == storedHash
        }
    }

    private fun migrateHashIfNeeded(apiKey: McpApiKey, secret: String) {
        if (!apiKey.keyHash.startsWith("$2a$") && !apiKey.keyHash.startsWith("$2b$")) {
            try {
                val bcryptHash = bcryptEncoder.encode(secret)!!
                apiKeyRepository.updateKeyHash(apiKey.id, bcryptHash)
                logger.info("Migrated API key {} from SHA-256 to BCrypt", apiKey.keyId)
            } catch (e: Exception) {
                logger.warn("Failed to migrate API key hash: {}", e.message)
            }
        }
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

    private fun fingerprint(secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretFingerprintKey)
        return java.util.HexFormat.of().formatHex(mac.doFinal(secret.toByteArray(Charsets.UTF_8)))
    }

    private fun touchLastUsed(apiKey: McpApiKey) {
        val now = LocalDateTime.now()
        val last = apiKey.lastUsedAt
        if (last == null || last.isBefore(now.minus(lastUsedWriteInterval))) {
            apiKeyRepository.updateLastUsedAt(apiKey.id, now)
        }
    }

    private fun getAllActiveKeys(): List<McpApiKey> {
        return try {
            // SECURITY: Use database-level filtering for efficiency and security
            // Avoids loading all keys into memory and filtering in application code
            apiKeyRepository.findAllActive()
        } catch (e: Exception) {
            logger.error("Failed to get all active keys", e)
            emptyList()
        }
    }

    private fun validateAndReturnKey(
        apiKey: McpApiKey,
        clientIp: String?,
        userAgent: String?,
        requestId: String?,
        startTime: Long
    ): AuthenticationResult {
        // Check if key is expired
        if (apiKey.isExpired()) {
            logAuthFailure(apiKey.id, "KEY_EXPIRED", "API key has expired", clientIp, userAgent, requestId)
            return createFailureResult(
                "KEY_EXPIRED",
                "API key has expired",
                clientIp, userAgent, requestId
            )
        }

        touchLastUsed(apiKey)

        // Log successful authentication
        logAuthSuccess(apiKey.id, apiKey.userId, null, clientIp, userAgent, requestId)

        val duration = System.currentTimeMillis() - startTime
        logger.debug("API key authentication successful for keyId={} in {}ms", apiKey.keyId, duration)

        return AuthenticationResult(
            success = true,
            apiKey = apiKey,
            errorCode = null,
            errorMessage = null
        )
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