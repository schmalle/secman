package com.secman.controller

import com.secman.domain.User
import com.secman.domain.UserProfilePicture
import com.secman.dto.UserProfileDto
import com.secman.repository.UserProfilePictureRepository
import com.secman.repository.UserRepository
import com.secman.service.AuditLogService
import com.secman.service.ProfilePictureService
import com.secman.service.WebAuthnService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.http.server.types.files.StreamedFile
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.transaction.Transactional
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.io.ByteArrayInputStream
import java.time.Instant

/**
 * Controller for user profile operations
 * Feature 028: User Profile Page
 * Feature 051: User Password Change
 * Feature: Profile Picture Management
 *
 * Endpoints:
 * - GET /api/users/profile: Returns current user's profile data
 * - PUT /api/users/profile/change-password: Changes current user's password
 * - GET/POST/DELETE /api/users/profile/picture: Manages the current user's avatar
 *
 * Security: All endpoints require authentication. Every route resolves its subject from
 * `authentication.name` and takes no user identifier as input, so cross-user access is
 * structurally impossible rather than merely checked.
 *
 * Route note: these paths are all two literal segments under /api/users, so they cannot collide
 * with the ADMIN-gated single-segment `@Get("/{id}")` on UserController, which shares the
 * /api/users prefix.
 */
@Controller("/api/users")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class UserProfileController(
    private val userRepository: UserRepository,
    private val webAuthnService: WebAuthnService,
    private val auditLogService: AuditLogService,
    private val profilePictureRepository: UserProfilePictureRepository,
    private val profilePictureService: ProfilePictureService
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
     * Metadata about the current user's avatar.
     * Feature: Profile Picture Management
     */
    @Serdeable
    data class ProfilePictureMetadata(
        val hasProfilePicture: Boolean,
        val contentType: String? = null,
        val fileSizeBytes: Long? = null,
        val width: Int? = null,
        val height: Int? = null,
        val updatedAt: Instant? = null
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
        // Blob-free projection - findByUserId would drag the LONGBLOB along.
        val pictureUpdatedAt = user.id?.let {
            profilePictureRepository.findUpdatedAtByUserId(it).orElse(null)
        }
        return HttpResponse.ok(UserProfileDto.fromUser(user, pictureUpdatedAt))
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
            return HttpResponse.badRequest(mapOf("error" to "An internal error occurred"))
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
        @Valid @Body request: MfaToggleRequest,
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
            return HttpResponse.badRequest(mapOf("error" to "An internal error occurred"))
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
        @Valid @Body request: ChangePasswordRequest,
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
        user.passwordHash = passwordEncoder.encode(request.newPassword)!!
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

    // ---------------------------------------------------------------------------------------
    // Profile picture
    //
    // Scope: own picture only. None of these routes accepts a user identifier - the subject is
    // always authentication.name - so there is no input an attacker could vary to reach another
    // user's avatar.
    // ---------------------------------------------------------------------------------------

    /**
     * Serve the current user's profile picture.
     *
     * GET /api/users/profile/picture
     *
     * Served inline (the only inline-disposition endpoint in the codebase). That is safe only
     * because the bytes are re-encoded rasters produced by ProfilePictureService and the
     * Content-Type is the stored, service-chosen value - the client's declared type is never
     * reflected back. nosniff is applied globally by SecurityHeadersFilter and repeated here to
     * document the intent at the endpoint that needs it most.
     *
     * SecurityHeadersFilter currently forces no-store on all /api/** responses, so the ETag is
     * inert today; it is emitted anyway so correctness is already in place if a caching carve-out
     * is ever added.
     */
    @Get("/profile/picture")
    open fun getProfilePicture(authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name).orElse(null)
            ?: return HttpResponse.notFound(ErrorResponse("User not found"))

        val picture = user.id?.let { profilePictureRepository.findByUserId(it).orElse(null) }
            ?: return HttpResponse.notFound(ErrorResponse("No profile picture set"))

        val extension = if (picture.contentType == ProfilePictureService.OUTPUT_JPEG) "jpg" else "png"
        return HttpResponse.ok(
            StreamedFile(ByteArrayInputStream(picture.content), MediaType.of(picture.contentType))
        )
            // Filename is synthesized from a Long and a constant - no user input reaches this header.
            .header("Content-Disposition", "inline; filename=\"avatar-${user.id}.$extension\"")
            .header("X-Content-Type-Options", "nosniff")
            .header("ETag", "\"${picture.sha256}\"")
    }

    /**
     * Upload or replace the current user's profile picture.
     *
     * POST /api/users/profile/picture (multipart/form-data, part name "file")
     *
     * Upsert semantics, so the UI never has to branch between "add" and "change".
     */
    @Post("/profile/picture", consumes = [MediaType.MULTIPART_FORM_DATA])
    @Transactional
    open fun uploadProfilePicture(
        @Part file: CompletedFileUpload,
        authentication: Authentication
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name).orElse(null)
            ?: return HttpResponse.notFound(ErrorResponse("User not found"))
        val userId = user.id
            ?: return HttpResponse.serverError(ErrorResponse("User is not persisted"))

        // Check the declared size before materializing the bytes onto the heap.
        if (file.size > MAX_UPLOAD_BYTES_HINT) {
            return rejectUpload(authentication, userId, "Image must be 2 MB or smaller")
        }

        val result = profilePictureService.normalize(
            bytes = file.bytes,
            filename = file.filename,
            declaredContentType = file.contentType.map { it.toString() }.orElse(null)
        )

        val normalized = when (result) {
            is ProfilePictureService.Result.Rejected ->
                return rejectUpload(authentication, userId, result.message)
            is ProfilePictureService.Result.Ok -> result
        }

        val now = Instant.now()
        val existing = profilePictureRepository.findByUserId(userId).orElse(null)
        val saved = if (existing != null) {
            existing.contentType = normalized.contentType
            existing.fileSizeBytes = normalized.bytes.size.toLong()
            existing.width = normalized.width
            existing.height = normalized.height
            existing.sha256 = normalized.sha256
            existing.originalFilename = normalized.originalFilename
            existing.content = normalized.bytes
            existing.updatedAt = now
            profilePictureRepository.update(existing)
        } else {
            profilePictureRepository.save(
                UserProfilePicture(
                    userId = userId,
                    contentType = normalized.contentType,
                    fileSizeBytes = normalized.bytes.size.toLong(),
                    width = normalized.width,
                    height = normalized.height,
                    sha256 = normalized.sha256,
                    originalFilename = normalized.originalFilename,
                    content = normalized.bytes,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        logger.info("Profile picture updated for user: {} (ID: {})", user.username, userId)
        auditLogService.logAction(
            authentication = authentication,
            action = "PROFILE_PICTURE_UPDATED",
            entityType = "User",
            entityId = userId,
            details = "Stored ${saved.width}x${saved.height} ${saved.contentType}, " +
                "${saved.fileSizeBytes} bytes (source: ${saved.originalFilename ?: "unnamed"})"
        )

        return HttpResponse.ok(
            ProfilePictureMetadata(
                hasProfilePicture = true,
                contentType = saved.contentType,
                fileSizeBytes = saved.fileSizeBytes,
                width = saved.width,
                height = saved.height,
                updatedAt = saved.updatedAt
            )
        )
    }

    /**
     * Remove the current user's profile picture.
     *
     * DELETE /api/users/profile/picture
     *
     * Idempotent: 204 whether or not a picture existed. Deliberate - the JS error scanner that
     * gates merges treats any status >= 400 as a failure, so a UI-reachable path must not return
     * 4xx in a normal flow.
     */
    @Delete("/profile/picture")
    @Transactional
    open fun deleteProfilePicture(authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name).orElse(null)
            ?: return HttpResponse.notFound(ErrorResponse("User not found"))
        val userId = user.id ?: return HttpResponse.status<Any>(HttpStatus.NO_CONTENT)

        val removed = profilePictureRepository.deleteByUserId(userId)
        if (removed > 0) {
            logger.info("Profile picture removed for user: {} (ID: {})", user.username, userId)
            auditLogService.logAction(
                authentication = authentication,
                action = "PROFILE_PICTURE_DELETED",
                entityType = "User",
                entityId = userId,
                details = "Profile picture removed via self-service"
            )
        }
        return HttpResponse.status<Any>(HttpStatus.NO_CONTENT)
    }

    /**
     * Audit and return a 400 for a rejected upload. Rejections are logged because a stream of
     * them is exactly the signal you want when someone is probing with polyglot files.
     */
    private fun rejectUpload(
        authentication: Authentication,
        userId: Long,
        message: String
    ): HttpResponse<*> {
        logger.warn("Profile picture upload rejected for user {}: {}", authentication.name, message)
        auditLogService.logAction(
            authentication = authentication,
            action = "PROFILE_PICTURE_UPLOAD_REJECTED",
            entityType = "User",
            entityId = userId,
            details = message
        )
        return HttpResponse.badRequest(ErrorResponse(message))
    }

    companion object {
        /**
         * Cheap pre-check on the declared part size so an oversized upload is refused before its
         * bytes are pulled onto the heap. ProfilePictureService re-checks the actual byte count
         * against its configured limit, which remains authoritative.
         */
        private const val MAX_UPLOAD_BYTES_HINT = 2L * 1024 * 1024
    }
}
