package com.secman.controller

import com.secman.domain.User
import com.secman.dto.UserProfileDto
import com.secman.repository.UserRepository
import com.secman.service.AuditLogService
import com.secman.service.WebAuthnService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/**
 * Controller for user profile operations
 * Feature 028: User Profile Page
 * Feature 051: User Password Change
 *
 * Endpoints:
 * - GET /api/users/profile: Returns current user's profile data
 * - PUT /api/users/profile/change-password: Changes current user's password
 *
 * Security: All endpoints require authentication
 */
@Controller("/api/users")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class UserProfileController(
    private val userRepository: UserRepository,
    private val webAuthnService: WebAuthnService,
    private val auditLogService: AuditLogService
) {
    private val logger = LoggerFactory.getLogger(UserProfileController::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()

    @Serdeable
    data class ErrorResponse(
        val message: String
    )

    @Serdeable
    data class MfaToggleRequest(
        val enabled: Boolean
    )

    @Serdeable
    data class MfaStatusResponse(
        val enabled: Boolean,
        val passkeyCount: Int,
        val canDisable: Boolean,
        val message: String? = null
    )

    /**
     * Request DTO for password change
     * Feature 051: User Password Change
     */
    @Serdeable
    data class ChangePasswordRequest(
        val currentPassword: String,
        val newPassword: String,
        val confirmPassword: String
    )

    /**
     * Response DTO for password change
     * Feature 051: User Password Change
     */
    @Serdeable
    data class ChangePasswordResponse(
        val success: Boolean,
        val message: String
    )

    /**
     * Get current user's profile
     *
     * Retrieves profile information (username, email, roles) for the authenticated user.
     * User is identified from the JWT token in the Authorization header.
     *
     * @param authentication Micronaut Security authentication object
     * @return HttpResponse with UserProfileDto or 404 error
     */
    @Get("/profile")
    fun getCurrentUserProfile(authentication: Authentication): HttpResponse<*> {
        val username = authentication.name
        logger.debug("Fetching profile for user: {}", username)

        val userOptional = userRepository.findByUsername(username)
        if (userOptional.isEmpty) {
            logger.warn("User not found: {}", username)
            return HttpResponse.notFound(ErrorResponse("User not found"))
        }

        val user = userOptional.get()
        logger.debug("Profile retrieved for user: {}", username)
        return HttpResponse.ok(UserProfileDto.fromUser(user))
    }

    /**
     * Get MFA status for current user
     * Feature: Passkey MFA Support
     *
     * GET /api/users/profile/mfa-status
     */
    @Get("/profile/mfa-status")
    fun getMfaStatus(authentication: Authentication): HttpResponse<*> {
        try {
            val username = authentication.name
            val user = userRepository.findByUsername(username).orElseThrow {
                IllegalArgumentException("User not found")
            }

            val passkeys = webAuthnService.getUserPasskeys(user)
            val passkeyCount = passkeys.size

            val response = MfaStatusResponse(
                enabled = user.mfaEnabled,
                passkeyCount = passkeyCount,
                canDisable = passkeyCount == 0 || !user.mfaEnabled,
                message = when {
                    user.mfaEnabled && passkeyCount == 0 -> "MFA is enabled but no passkeys are registered. Please register a passkey."
                    user.mfaEnabled && passkeyCount > 0 -> "MFA is enabled with $passkeyCount passkey(s) registered."
                    !user.mfaEnabled && passkeyCount > 0 -> "MFA is disabled. You have $passkeyCount passkey(s) registered but not actively used."
                    else -> "MFA is disabled. Register a passkey to enable MFA."
                }
            )

            return HttpResponse.ok(response)

        } catch (e: Exception) {
            logger.error("Failed to get MFA status", e)
            return HttpResponse.badRequest(mapOf("error" to "Failed to get MFA status: ${e.message}"))
        }
    }

    /**
     * Toggle MFA on/off for current user
     * Feature: Passkey MFA Support
     *
     * PUT /api/users/profile/mfa-toggle
     */
    @Put("/profile/mfa-toggle")
    @Transactional
    open fun toggleMfa(
        @Body request: MfaToggleRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        try {
            val username = authentication.name
            val user = userRepository.findByUsername(username).orElseThrow {
                IllegalArgumentException("User not found")
            }

            // Check if user has passkeys when enabling MFA
            if (request.enabled) {
                val passkeyCount = webAuthnService.getUserPasskeys(user).size
                if (passkeyCount == 0) {
                    return HttpResponse.badRequest(mapOf(
                        "error" to "Cannot enable MFA without registering at least one passkey",
                        "message" to "Please register a passkey before enabling MFA"
                    ))
                }
            }

            user.mfaEnabled = request.enabled
            userRepository.update(user)

            logger.info("User ${user.username} (ID: ${user.id}) ${if (request.enabled) "enabled" else "disabled"} MFA")

            return HttpResponse.ok(mapOf(
                "success" to true,
                "mfaEnabled" to user.mfaEnabled,
                "message" to "MFA ${if (request.enabled) "enabled" else "disabled"} successfully"
            ))

        } catch (e: Exception) {
            logger.error("Failed to toggle MFA", e)
            return HttpResponse.badRequest(mapOf("error" to "Failed to toggle MFA: ${e.message}"))
        }
    }

    /**
     * Change current user's password
     * Feature 051: User Password Change
     *
     * PUT /api/users/profile/change-password
     *
     * Requirements:
     * - FR-001: Accessible to authenticated users with local accounts
     * - FR-002: Requires current password verification
     * - FR-003, FR-004: Requires new password and confirmation to match
     * - FR-005: Minimum 8 characters
     * - FR-006: Must differ from current password
     * - FR-007: Clear error messages
     * - FR-008: Success message on completion
     * - FR-009: OAuth users cannot change password
     * - FR-010: Secure password hashing
     */
    @Put("/profile/change-password")
    @Transactional
    open fun changePassword(
        @Body request: ChangePasswordRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        val username = authentication.name
        logger.debug("Password change request for user: {}", username)

        // Find user
        val userOptional = userRepository.findByUsername(username)
        if (userOptional.isEmpty) {
            logger.warn("Password change attempted for non-existent user: {}", username)
            return HttpResponse.notFound(ChangePasswordResponse(false, "User not found"))
        }

        val user = userOptional.get()

        // FR-009: Check if user can change password (not OAuth-only)
        if (user.authSource == User.AuthSource.OAUTH) {
            logger.warn("OAuth user {} attempted to change password", username)
            // FR-011: Audit log failed attempt
            auditLogService.logAction(
                authentication = authentication,
                action = "PASSWORD_CHANGE_FAILED",
                entityType = "User",
                entityId = user.id,
                details = "OAuth user attempted password change"
            )
            return HttpResponse.status<ChangePasswordResponse>(HttpStatus.FORBIDDEN)
                .body(ChangePasswordResponse(false, "Password change is not available for OAuth accounts"))
        }

        // FR-002: Verify current password
        if (!passwordEncoder.matches(request.currentPassword, user.passwordHash)) {
            logger.warn("Invalid current password for user: {}", username)
            // FR-011: Audit log failed attempt
            auditLogService.logAction(
                authentication = authentication,
                action = "PASSWORD_CHANGE_FAILED",
                entityType = "User",
                entityId = user.id,
                details = "Invalid current password provided"
            )
            return HttpResponse.badRequest(ChangePasswordResponse(false, "Current password is incorrect"))
        }

        // FR-003, FR-004: Verify new password and confirmation match
        if (request.newPassword != request.confirmPassword) {
            return HttpResponse.badRequest(ChangePasswordResponse(false, "New password and confirmation do not match"))
        }

        // FR-005: Minimum length validation
        if (request.newPassword.length < 8) {
            return HttpResponse.badRequest(ChangePasswordResponse(false, "Password must be at least 8 characters"))
        }

        // FR-020 (from US2): Max length validation
        if (request.newPassword.length > 200) {
            return HttpResponse.badRequest(ChangePasswordResponse(false, "Password exceeds maximum length"))
        }

        // FR-006: Must differ from current password
        if (passwordEncoder.matches(request.newPassword, user.passwordHash)) {
            return HttpResponse.badRequest(ChangePasswordResponse(false, "New password must be different from current password"))
        }

        // FR-010: Update password with secure hashing
        user.passwordHash = passwordEncoder.encode(request.newPassword)
        userRepository.update(user)

        logger.info("Password changed successfully for user: {} (ID: {})", username, user.id)

        // FR-011: Audit log successful password change
        auditLogService.logAction(
            authentication = authentication,
            action = "PASSWORD_CHANGED",
            entityType = "User",
            entityId = user.id,
            details = "Password changed successfully via self-service"
        )

        // FR-008: Success message
        return HttpResponse.ok(ChangePasswordResponse(true, "Password changed successfully"))
    }
}
