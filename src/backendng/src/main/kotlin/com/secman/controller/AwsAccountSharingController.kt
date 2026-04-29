package com.secman.controller

import com.secman.dto.CreateAwsAccountSharingRequest
import com.secman.domain.MappingStatus
import com.secman.service.AwsAccountSharingService
import com.secman.service.DuplicateSharingException
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.Valid
import org.slf4j.LoggerFactory

/**
 * REST controller for AWS Account Sharing management.
 *
 * Feature: AWS Account Sharing
 *
 * - ADMIN / SECCHAMPION: full management — list all rules, create with any source, delete any rule
 * - Everyone else (including VULN): scoped — list only rules where they are source OR target,
 *   create only where they are source, delete only their own outgoing rules
 */
@Controller("/api/aws-account-sharing")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class AwsAccountSharingController(
    private val awsAccountSharingService: AwsAccountSharingService,
    private val userRepository: UserRepository,
    private val userMappingRepository: UserMappingRepository
) {
    private val logger = LoggerFactory.getLogger(AwsAccountSharingController::class.java)

    /**
     * GET /api/aws-account-sharing - List sharing rules.
     * ADMIN/SECCHAMPION see all rules; every other authenticated user (including VULN)
     * sees only rules where they are source OR target.
     */
    @Get
    fun listSharingRules(
        @QueryValue("page") page: Int?,
        @QueryValue("size") size: Int?,
        authentication: Authentication
    ): HttpResponse<Map<String, Any>> {
        logger.debug("Listing AWS account sharing rules: page=$page, size=$size")

        return try {
            val result = if (hasFullViewAccess(authentication)) {
                awsAccountSharingService.listSharingRules(page ?: 0, size ?: 20)
            } else {
                // Non-privileged users (including VULN) see only rules they are
                // personally involved in — as source OR target.
                val userId = getUserIdFromAuthentication(authentication)
                awsAccountSharingService.listSharingRulesForInvolvedUser(userId, page ?: 0, size ?: 20)
            }
            HttpResponse.ok(result)
        } catch (e: Exception) {
            logger.error("Failed to list AWS account sharing rules", e)
            HttpResponse.serverError(mapOf(
                "error" to "Internal Server Error",
                "message" to "Failed to list sharing rules"
            ))
        }
    }

    /**
     * POST /api/aws-account-sharing - Create a new sharing rule.
     * ADMIN/SECCHAMPION can create any rule; other users (including VULN)
     * can only create rules where they are the source.
     */
    @Post
    open fun createSharingRule(
        @Valid @Body request: CreateAwsAccountSharingRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info(
            "Creating AWS account sharing rule: sourceId={}, sourceEmail={}, targetId={}, targetEmail={}",
            request.sourceUserId, request.sourceUserEmail, request.targetUserId, request.targetUserEmail
        )

        return try {
            val currentUserId = getUserIdFromAuthentication(authentication)

            // Users without manage-any access can only share their own accounts —
            // the request must not name a different source user by id or email.
            val effectiveRequest = if (!hasFullManagementAccess(authentication)) {
                val triesDifferentId = request.sourceUserId != null && request.sourceUserId != currentUserId
                val triesForeignEmail = !request.sourceUserEmail.isNullOrBlank() &&
                    !userRepository.findById(currentUserId).map { it.email.equals(request.sourceUserEmail, ignoreCase = true) }.orElse(false)
                if (triesDifferentId || triesForeignEmail) {
                    return HttpResponse.status<Any>(HttpStatus.FORBIDDEN).body(mapOf(
                        "error" to "Forbidden",
                        "message" to "You can only share your own AWS accounts"
                    ))
                }
                // Force source to the caller regardless of what the client sent.
                request.copy(sourceUserId = currentUserId, sourceUserEmail = null)
            } else {
                request
            }

            val result = awsAccountSharingService.createSharingRule(effectiveRequest, currentUserId)
            HttpResponse.status<Any>(HttpStatus.CREATED).body(result)
        } catch (e: IllegalArgumentException) {
            logger.warn("AWS account sharing creation failed: ${e.message}")
            HttpResponse.badRequest(mapOf(
                "error" to "Validation Error",
                "message" to (e.message ?: "Invalid request")
            ))
        } catch (e: DuplicateSharingException) {
            logger.warn("AWS account sharing duplicate: ${e.message}")
            HttpResponse.status<Map<String, String>>(HttpStatus.CONFLICT).body(mapOf(
                "error" to "Conflict",
                "message" to (e.message ?: "Sharing rule already exists")
            ))
        } catch (e: IllegalStateException) {
            logger.error("AWS account sharing creation failed (server state): ${e.message}", e)
            HttpResponse.serverError(mapOf(
                "error" to "Internal Server Error",
                "message" to (e.message ?: "Server-side state error")
            ))
        } catch (e: NoSuchElementException) {
            HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to (e.message ?: "User not found")
            ))
        }
    }

    /**
     * DELETE /api/aws-account-sharing/{id} - Delete a sharing rule.
     * ADMIN/SECCHAMPION can delete any rule; other users (including VULN)
     * can only delete rules where they are the source.
     */
    @Delete("/{id}")
    fun deleteSharingRule(
        @PathVariable id: Long,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info("Deleting AWS account sharing rule: id=$id")

        return try {
            // Users without manage-any access can only delete their own rules
            if (!hasFullManagementAccess(authentication)) {
                val currentUserId = getUserIdFromAuthentication(authentication)
                if (!awsAccountSharingService.isOwnedByUser(id, currentUserId)) {
                    return HttpResponse.status<Any>(HttpStatus.FORBIDDEN).body(mapOf(
                        "error" to "Forbidden",
                        "message" to "You can only delete your own sharing rules"
                    ))
                }
            }

            awsAccountSharingService.deleteSharingRule(id)
            HttpResponse.noContent<Void>()
        } catch (e: NoSuchElementException) {
            logger.warn("AWS account sharing rule not found: $id")
            HttpResponse.notFound<Any>()
        }
    }

    private fun getUserIdFromAuthentication(authentication: Authentication): Long {
        val userId = authentication.attributes["userId"]
        return when (userId) {
            is Long -> userId
            is Int -> userId.toLong()
            is String -> userId.toLong()
            else -> throw IllegalStateException("Unable to determine user ID from authentication")
        }
    }

    /**
     * GET /api/aws-account-sharing/users - Lightweight user list for the sharing form.
     *
     * Returns minimal user info for populating source/target dropdowns. Includes:
     *  - All existing Users (isPending = false)
     *  - Emails known only via PENDING UserMappings (users recorded in AWS
     *    account or domain mappings but who have never logged in).
     *    For these entries `id = null`, `isPending = true`, and the UI must
     *    submit them by email — the backend will create the User record on
     *    rule creation so the FK constraint is satisfied.
     *
     * Available to all authenticated users.
     */
    @Get("/users")
    fun listUsersForSharing(): HttpResponse<List<SharingUserResponse>> {
        val activeUsers = userRepository.findAll().map { user ->
            SharingUserResponse(
                id = user.id,
                username = user.username,
                email = user.email,
                isPending = false
            )
        }
        val activeEmails = activeUsers.mapNotNull { it.email.lowercase().takeIf { e -> e.isNotBlank() } }.toHashSet()

        // Distinct emails from PENDING mappings that don't already correspond to a real User.
        // findByStatus(PENDING) reliably lists mappings with no user_id link.
        val pendingEntries = userMappingRepository.findByStatus(MappingStatus.PENDING)
            .asSequence()
            .map { it.email.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .filter { it.lowercase() !in activeEmails }
            .map { email ->
                SharingUserResponse(
                    id = null,
                    username = email.substringBefore("@").ifBlank { email },
                    email = email,
                    isPending = true
                )
            }
            .toList()

        val combined = (activeUsers + pendingEntries).sortedBy { it.email.lowercase() }
        return HttpResponse.ok(combined)
    }

    /**
     * View-all access — ADMIN or SECCHAMPION.
     * Allowed to see every sharing rule in the system.
     * All other authenticated users (including VULN) see only rules where they
     * are source OR target — handled via listSharingRulesForInvolvedUser.
     */
    private fun hasFullViewAccess(authentication: Authentication): Boolean {
        return authentication.roles.contains("ADMIN") ||
            authentication.roles.contains("SECCHAMPION")
    }

    /**
     * Manage-any access — ADMIN or SECCHAMPION.
     * Allowed to create rules with any sourceUserId and delete any rule.
     * Other authenticated users (including VULN) can only create rules with
     * themselves as the source and delete rules they own.
     */
    private fun hasFullManagementAccess(authentication: Authentication): Boolean {
        return authentication.roles.contains("ADMIN") ||
            authentication.roles.contains("SECCHAMPION")
    }

    @Serdeable
    data class SharingUserResponse(
        // null for pending users — the client must submit them by email and
        // the backend lazily creates a User record when a sharing rule is saved.
        val id: Long?,
        val username: String,
        val email: String,
        val isPending: Boolean = false
    )
}
