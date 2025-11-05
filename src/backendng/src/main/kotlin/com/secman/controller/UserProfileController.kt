package com.secman.controller

import com.secman.dto.UserProfileDto
import com.secman.repository.UserRepository
import com.secman.service.WebAuthnService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/**
 * Controller for user profile operations
 * Feature 028: User Profile Page
 *
 * Endpoints:
 * - GET /api/users/profile: Returns current user's profile data
 *
 * Security: All endpoints require authentication
 */
@Controller("/api/users")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class UserProfileController(
    private val userRepository: UserRepository,
    private val webAuthnService: WebAuthnService
) {
    private val logger = LoggerFactory.getLogger(UserProfileController::class.java)

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
}
