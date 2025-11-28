package com.secman.dto.mcp

import com.secman.domain.McpPermission
import com.secman.domain.User
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

/**
 * DTOs for MCP User Delegation feature.
 * Feature: 050-mcp-user-delegation
 */

/**
 * Result of a delegation validation attempt.
 */
@Serdeable
data class DelegationValidationResult(
    val success: Boolean,
    val user: User? = null,
    val effectivePermissions: Set<McpPermission> = emptySet(),
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun success(user: User, permissions: Set<McpPermission>): DelegationValidationResult {
            return DelegationValidationResult(
                success = true,
                user = user,
                effectivePermissions = permissions
            )
        }

        fun failure(errorCode: String, errorMessage: String): DelegationValidationResult {
            return DelegationValidationResult(
                success = false,
                errorCode = errorCode,
                errorMessage = errorMessage
            )
        }
    }
}

/**
 * Record of a delegation failure for threshold tracking.
 */
@Serdeable
data class DelegationFailureRecord(
    val apiKeyId: Long,
    val timestamp: LocalDateTime,
    val email: String,
    val reason: String
)

/**
 * Delegation error codes for consistent error responses.
 */
object DelegationErrorCodes {
    /** Delegation is not enabled for this API key */
    const val DELEGATION_NOT_ENABLED = "DELEGATION_NOT_ENABLED"

    /** Email domain is not in the allowed list for this API key */
    const val DELEGATION_DOMAIN_REJECTED = "DELEGATION_DOMAIN_REJECTED"

    /** User with the specified email was not found */
    const val DELEGATION_USER_NOT_FOUND = "DELEGATION_USER_NOT_FOUND"

    /** User account is disabled or inactive */
    const val DELEGATION_USER_INACTIVE = "DELEGATION_USER_INACTIVE"

    /** Email format is invalid */
    const val DELEGATION_INVALID_EMAIL = "DELEGATION_INVALID_EMAIL"

    /** General delegation failure */
    const val DELEGATION_FAILED = "DELEGATION_FAILED"
}

/**
 * Request DTO for creating/updating an API key with delegation settings.
 */
@Serdeable
data class McpApiKeyDelegationRequest(
    val delegationEnabled: Boolean = false,
    val allowedDelegationDomains: String? = null
) {
    /**
     * Validate that if delegation is enabled, domains are provided.
     */
    fun validate(): ValidationResult {
        if (delegationEnabled && allowedDelegationDomains.isNullOrBlank()) {
            return ValidationResult(
                valid = false,
                error = "Delegation enabled but no allowed domains specified",
                field = "allowedDelegationDomains"
            )
        }

        if (delegationEnabled && allowedDelegationDomains != null) {
            val domains = allowedDelegationDomains.split(",").map { it.trim() }
            for (domain in domains) {
                if (!domain.startsWith("@")) {
                    return ValidationResult(
                        valid = false,
                        error = "Invalid domain format: '$domain' (must start with @)",
                        field = "allowedDelegationDomains"
                    )
                }
                if (!domain.contains(".")) {
                    return ValidationResult(
                        valid = false,
                        error = "Invalid domain format: '$domain' (must contain a TLD)",
                        field = "allowedDelegationDomains"
                    )
                }
            }
        }

        return ValidationResult(valid = true)
    }
}

/**
 * Result of delegation request validation.
 */
@Serdeable
data class ValidationResult(
    val valid: Boolean,
    val error: String? = null,
    val field: String? = null
)

/**
 * Response DTO for API key with delegation fields.
 */
@Serdeable
data class McpApiKeyDelegationResponse(
    val delegationEnabled: Boolean,
    val allowedDelegationDomains: String?,
    val domainCount: Int
)
