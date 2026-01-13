package com.secman.service

import com.secman.domain.McpApiKey
import com.secman.domain.McpPermission
import com.secman.domain.User
import com.secman.dto.mcp.DelegationErrorCodes
import com.secman.dto.mcp.DelegationFailureRecord
import com.secman.dto.mcp.DelegationValidationResult
import com.secman.repository.UserRepository
import io.micronaut.context.annotation.Value
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Service for MCP User Delegation feature.
 * Handles email passthrough validation, permission computation, and failure tracking.
 *
 * Feature: 050-mcp-user-delegation
 *
 * Delegation Flow:
 * 1. External tool passes X-MCP-User-Email header with authenticated user email
 * 2. System validates API key has delegation enabled
 * 3. System validates email domain against allowed domains
 * 4. System looks up user and computes effective permissions (intersection)
 * 5. Operations execute with delegated user's access rights
 */
@Singleton
class McpDelegationService {

    private val logger = LoggerFactory.getLogger(McpDelegationService::class.java)

    @Inject
    lateinit var userRepository: UserRepository

    /**
     * Alert threshold for failed delegation attempts.
     * When exceeded within the time window, an alert is triggered.
     */
    @Value("\${secman.mcp.delegation.alert.threshold:10}")
    var alertThreshold: Int = 10

    /**
     * Time window in minutes for threshold calculation.
     */
    @Value("\${secman.mcp.delegation.alert.window-minutes:5}")
    var alertWindowMinutes: Int = 5

    /**
     * In-memory storage for delegation failures per API key.
     * Key: API key ID, Value: Queue of failure records (sliding window)
     *
     * SECURITY NOTE (HIGH-006): This in-memory storage has limitations:
     * - Data is lost on application restart
     * - Not shared across multiple application instances in a cluster
     *
     * For production environments with high availability requirements or
     * strict security audit needs, consider implementing database-backed
     * failure tracking (e.g., DelegationFailureLog entity with scheduled cleanup).
     *
     * Current mitigations:
     * - Sliding window cleanup prevents unbounded memory growth
     * - Alert threshold triggers immediate warning for investigation
     * - MCP audit logs capture all delegation events for forensics
     */
    private val failureTracker = ConcurrentHashMap<Long, ConcurrentLinkedDeque<DelegationFailureRecord>>()

    /**
     * Mapping of user roles to implied MCP permissions.
     * Used to compute effective permissions during delegation.
     *
     * From research.md:
     * - USER: Basic read access
     * - ADMIN: All permissions
     * - VULN: Vulnerability and scan access
     * - RELEASE_MANAGER: Requirements and assessments
     * - REQ: Requirements access
     * - RISK: Assessments access
     * - SECCHAMPION: Combined Risk + Req + Vuln access
     */
    private val roleToPermissions: Map<User.Role, Set<McpPermission>> = mapOf(
        User.Role.USER to setOf(
            McpPermission.REQUIREMENTS_READ,
            McpPermission.ASSETS_READ,
            McpPermission.VULNERABILITIES_READ,
            McpPermission.TAGS_READ
        ),
        User.Role.ADMIN to McpPermission.entries.toSet(),
        User.Role.VULN to setOf(
            McpPermission.VULNERABILITIES_READ,
            McpPermission.SCANS_READ,
            McpPermission.ASSETS_READ
        ),
        User.Role.RELEASE_MANAGER to setOf(
            McpPermission.REQUIREMENTS_READ,
            McpPermission.ASSESSMENTS_READ
        ),
        User.Role.REQ to setOf(
            McpPermission.REQUIREMENTS_READ,
            McpPermission.REQUIREMENTS_WRITE,
            McpPermission.FILES_READ,
            McpPermission.TAGS_READ
        ),
        User.Role.RISK to setOf(
            McpPermission.ASSESSMENTS_READ,
            McpPermission.ASSESSMENTS_WRITE,
            McpPermission.ASSESSMENTS_EXECUTE
        ),
        User.Role.SECCHAMPION to setOf(
            McpPermission.REQUIREMENTS_READ,
            McpPermission.ASSESSMENTS_READ,
            McpPermission.ASSETS_READ,
            McpPermission.VULNERABILITIES_READ,
            McpPermission.SCANS_READ
        )
    )

    /**
     * Validate a delegation request.
     *
     * Supports both email and username as identifier:
     * - If identifier contains '@', treat as email (validate format, check domain)
     * - Otherwise, treat as username (skip email validation and domain check)
     *
     * @param apiKey The MCP API key making the request
     * @param identifier The delegated user's email address or username
     * @return DelegationValidationResult with success/failure details
     */
    fun validateDelegation(apiKey: McpApiKey, identifier: String): DelegationValidationResult {
        logger.debug("Validating delegation: apiKeyId={}, identifier={}", apiKey.id, identifier)

        // Check if delegation is enabled for this API key
        if (!apiKey.delegationEnabled) {
            logger.debug("Delegation not enabled for API key: {}", apiKey.keyId)
            recordFailure(apiKey.id, identifier, DelegationErrorCodes.DELEGATION_NOT_ENABLED)
            return DelegationValidationResult.failure(
                DelegationErrorCodes.DELEGATION_NOT_ENABLED,
                "Delegation is not enabled for this API key"
            )
        }

        // Determine if identifier is email or username
        val isEmail = identifier.contains("@")

        // Look up user based on identifier type
        val user = if (isEmail) {
            // Email path: validate format and domain, then lookup
            if (!isValidEmail(identifier)) {
                logger.debug("Invalid email format for delegation: {}", identifier)
                recordFailure(apiKey.id, identifier, DelegationErrorCodes.DELEGATION_INVALID_EMAIL)
                return DelegationValidationResult.failure(
                    DelegationErrorCodes.DELEGATION_INVALID_EMAIL,
                    "Invalid email format: $identifier"
                )
            }

            if (!apiKey.isDelegationAllowedForEmail(identifier)) {
                logger.warn("Domain rejected for delegation: email={}, apiKey={}", identifier, apiKey.keyId)
                recordFailure(apiKey.id, identifier, DelegationErrorCodes.DELEGATION_DOMAIN_REJECTED)
                return DelegationValidationResult.failure(
                    DelegationErrorCodes.DELEGATION_DOMAIN_REJECTED,
                    "Email domain is not in the allowed list for this API key"
                )
            }

            userRepository.findByEmailIgnoreCase(identifier).orElse(null)
        } else {
            // Username path: skip email validation and domain check
            logger.debug("Using username lookup for delegation: {}", identifier)
            userRepository.findByUsername(identifier).orElse(null)
        }

        if (user == null) {
            val identifierType = if (isEmail) "email" else "username"
            logger.warn("User not found for delegation: {}={}", identifierType, identifier)
            recordFailure(apiKey.id, identifier, DelegationErrorCodes.DELEGATION_USER_NOT_FOUND)
            return DelegationValidationResult.failure(
                DelegationErrorCodes.DELEGATION_USER_NOT_FOUND,
                "User with $identifierType '$identifier' not found"
            )
        }

        // Compute effective permissions (intersection of user roles and API key permissions)
        val effectivePermissions = computeEffectivePermissions(user, apiKey)

        logger.info(
            "Delegation validated successfully: identifier={}, user={}, apiKey={}, effectivePermissions={}",
            identifier, user.username, apiKey.keyId, effectivePermissions.size
        )

        return DelegationValidationResult.success(user, effectivePermissions)
    }

    /**
     * Compute the effective permissions for a delegated user.
     *
     * The effective permissions are the INTERSECTION of:
     * 1. Permissions implied by the user's roles (mapped via roleToPermissions)
     * 2. Permissions granted to the API key
     *
     * This implements defense-in-depth: the delegated user can never have
     * more permissions than either their roles allow OR the API key allows.
     *
     * @param user The delegated user
     * @param apiKey The MCP API key
     * @return Set of effective MCP permissions
     */
    fun computeEffectivePermissions(user: User, apiKey: McpApiKey): Set<McpPermission> {
        // Get permissions implied by user's roles
        val userImpliedPermissions = user.roles
            .flatMap { role -> roleToPermissions[role] ?: emptySet() }
            .toSet()

        // Get API key's permissions
        val apiKeyPermissions = apiKey.getPermissionSet()

        // Compute intersection
        val effectivePermissions = userImpliedPermissions.intersect(apiKeyPermissions)

        logger.debug(
            "Computed effective permissions: user={}, userPermissions={}, apiKeyPermissions={}, effective={}",
            user.email,
            userImpliedPermissions.size,
            apiKeyPermissions.size,
            effectivePermissions.size
        )

        return effectivePermissions
    }

    /**
     * Get the permissions implied by a user's roles.
     *
     * @param user The user
     * @return Set of MCP permissions implied by the user's roles
     */
    fun getUserImpliedPermissions(user: User): Set<McpPermission> {
        return user.roles
            .flatMap { role -> roleToPermissions[role] ?: emptySet() }
            .toSet()
    }

    /**
     * Record a delegation failure for threshold tracking.
     *
     * @param apiKeyId The ID of the API key
     * @param email The email that failed delegation
     * @param reason The failure reason code
     */
    private fun recordFailure(apiKeyId: Long, email: String, reason: String) {
        val record = DelegationFailureRecord(
            apiKeyId = apiKeyId,
            timestamp = LocalDateTime.now(),
            email = email,
            reason = reason
        )

        val failures = failureTracker.computeIfAbsent(apiKeyId) { ConcurrentLinkedDeque() }
        failures.addLast(record)

        // Clean up old entries outside the time window
        val windowStart = LocalDateTime.now().minusMinutes(alertWindowMinutes.toLong())
        while (failures.peekFirst()?.timestamp?.isBefore(windowStart) == true) {
            failures.pollFirst()
        }

        // Check if threshold is exceeded
        if (failures.size >= alertThreshold) {
            triggerAlert(apiKeyId, failures.size)
        }
    }

    /**
     * Trigger an alert for excessive delegation failures.
     * Currently logs at WARN level; can be extended to send notifications.
     *
     * @param apiKeyId The ID of the API key
     * @param failureCount The number of failures in the time window
     */
    private fun triggerAlert(apiKeyId: Long, failureCount: Int) {
        logger.warn(
            "DELEGATION ALERT: API key {} has {} failed delegation attempts in the last {} minutes. " +
            "Threshold: {}. Possible abuse or misconfiguration.",
            apiKeyId, failureCount, alertWindowMinutes, alertThreshold
        )
        // TODO: Integrate with notification service if available
    }

    /**
     * Get the current failure count for an API key within the time window.
     * Useful for monitoring and testing.
     *
     * @param apiKeyId The ID of the API key
     * @return The number of failures in the current time window
     */
    fun getFailureCount(apiKeyId: Long): Int {
        val failures = failureTracker[apiKeyId] ?: return 0
        val windowStart = LocalDateTime.now().minusMinutes(alertWindowMinutes.toLong())
        return failures.count { it.timestamp.isAfter(windowStart) }
    }

    /**
     * Clear failure records for an API key.
     * Useful for testing and administrative purposes.
     *
     * @param apiKeyId The ID of the API key
     */
    fun clearFailures(apiKeyId: Long) {
        failureTracker.remove(apiKeyId)
    }

    /**
     * Validate email format.
     *
     * @param email The email to validate
     * @return true if the email format is valid
     */
    private fun isValidEmail(email: String): Boolean {
        if (email.isBlank()) return false
        val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\$".toRegex()
        return emailRegex.matches(email)
    }

    /**
     * Check if delegation would be allowed for a given API key and email.
     * Does NOT record failures or look up the user - use for validation preview.
     *
     * @param apiKey The MCP API key
     * @param email The email to check
     * @return true if delegation would be allowed (structurally), false otherwise
     */
    fun isDelegationStructurallyAllowed(apiKey: McpApiKey, email: String): Boolean {
        return apiKey.delegationEnabled &&
               isValidEmail(email) &&
               apiKey.isDelegationAllowedForEmail(email)
    }
}
