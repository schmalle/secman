package com.secman.controller

import com.secman.dto.CreateAwsAccountSharingRequest
import com.secman.dto.SourceAwsAccountResponse
import com.secman.dto.UpdateAwsAccountSharingRequest
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

            // Invite-mode validation. The service trusts these checks have
            // already run; do NOT move them into the service unless you also
            // remove them from here and update the design doc.
            if (effectiveRequest.inviteByEmail) {
                val email = effectiveRequest.targetUserEmail?.trim().orEmpty()
                if (!com.secman.util.EmailDomain.isWellFormed(email)) {
                    return HttpResponse.badRequest(mapOf(
                        "error" to "Validation Error",
                        "message" to "A valid email address is required to invite a new user"
                    ))
                }

                val callerEmail = userRepository.findById(currentUserId)
                    .map { it.email }.orElse(null)
                val callerDomain = com.secman.util.EmailDomain.extractDomain(callerEmail)
                if (callerDomain.isNullOrBlank()) {
                    return HttpResponse.serverError(mapOf(
                        "error" to "Internal Server Error",
                        "message" to "Cannot determine your email domain — contact an administrator"
                    ))
                }

                if (!com.secman.util.EmailDomain.sameDomain(callerEmail, email)) {
                    return HttpResponse.badRequest(mapOf(
                        "error" to "Validation Error",
                        "message" to "You can only invite users whose email matches your own domain (@$callerDomain)"
                    ))
                }

                if (email.equals(callerEmail, ignoreCase = true)) {
                    return HttpResponse.badRequest(mapOf(
                        "error" to "Validation Error",
                        "message" to "You cannot share with yourself"
                    ))
                }

                if (userRepository.findByEmailIgnoreCase(email).isPresent) {
                    return HttpResponse.status<Any>(HttpStatus.CONFLICT).body(mapOf(
                        "error" to "Conflict",
                        "message" to "This email is already a SecMan user — use 'Pick existing user' instead"
                    ))
                }

                val pendingHits = userMappingRepository.findByEmailAndStatus(email, MappingStatus.PENDING)
                if (pendingHits.isNotEmpty()) {
                    return HttpResponse.status<Any>(HttpStatus.CONFLICT).body(mapOf(
                        "error" to "Conflict",
                        "message" to "This email is already known via mapping import — use 'Pick existing user' instead"
                    ))
                }
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
     * PUT /api/aws-account-sharing/{id} - Edit the per-account selection
     * on an existing sharing rule. Source and target are immutable on the
     * row — to change them, delete and recreate.
     *
     * Authorization mirrors create/delete: ADMIN/SECCHAMPION can edit any
     * rule; other users can only edit rules where they are the source.
     */
    @Put("/{id}")
    open fun updateSharingRule(
        @PathVariable id: Long,
        @Valid @Body request: UpdateAwsAccountSharingRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info(
            "Updating AWS account sharing rule: id={}, accountIds={}",
            id, request.awsAccountIds?.size ?: 0
        )

        return try {
            if (!hasFullManagementAccess(authentication)) {
                val currentUserId = getUserIdFromAuthentication(authentication)
                if (!awsAccountSharingService.isOwnedByUser(id, currentUserId)) {
                    return HttpResponse.status<Any>(HttpStatus.FORBIDDEN).body(mapOf(
                        "error" to "Forbidden",
                        "message" to "You can only edit your own sharing rules"
                    ))
                }
            }

            val result = awsAccountSharingService.updateSharingRule(id, request)
            HttpResponse.ok(result)
        } catch (e: IllegalArgumentException) {
            logger.warn("AWS account sharing update failed: ${e.message}")
            HttpResponse.badRequest(mapOf(
                "error" to "Validation Error",
                "message" to (e.message ?: "Invalid request")
            ))
        } catch (e: NoSuchElementException) {
            HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to (e.message ?: "Sharing rule not found")
            ))
        }
    }

    /**
     * GET /api/aws-account-sharing/source-accounts - List the AWS account
     * IDs available for sharing on behalf of a given source user. Used to
     * populate the per-account picker on the create/edit form.
     *
     * One of `userId` or `email` must be provided. Non-privileged users
     * may only query their own accounts (matched by id-equality or by
     * case-insensitive email).
     */
    @Get("/source-accounts")
    fun listSourceAccounts(
        @QueryValue("userId") userId: Long?,
        @QueryValue("email") email: String?,
        authentication: Authentication
    ): HttpResponse<*> {
        if (userId == null && email.isNullOrBlank()) {
            return HttpResponse.badRequest(mapOf(
                "error" to "Validation Error",
                "message" to "Either userId or email must be provided"
            ))
        }

        if (!hasFullViewAccess(authentication)) {
            // Non-privileged users can only enumerate THEIR OWN accounts —
            // the picker only matters for them when they're sharing-out.
            val currentUserId = getUserIdFromAuthentication(authentication)
            val currentUser = userRepository.findById(currentUserId).orElse(null)
                ?: return HttpResponse.status<Any>(HttpStatus.FORBIDDEN).body(mapOf(
                    "error" to "Forbidden",
                    "message" to "Current user not found"
                ))
            val mismatchById = userId != null && userId != currentUserId
            val mismatchByEmail = !email.isNullOrBlank() && !email.equals(currentUser.email, ignoreCase = true)
            if (mismatchById || mismatchByEmail) {
                return HttpResponse.status<Any>(HttpStatus.FORBIDDEN).body(mapOf(
                    "error" to "Forbidden",
                    "message" to "You can only list your own AWS accounts"
                ))
            }
        }

        val accountIds = if (userId != null) {
            awsAccountSharingService.listSourceAccountIds(userId)
        } else {
            awsAccountSharingService.listSourceAccountIdsByEmail(email!!)
        }
        return HttpResponse.ok(accountIds.map { SourceAwsAccountResponse(it) })
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
     *
     * @deprecated Will be replaced by `GET /api/users?includePending=true` once
     * that endpoint is opened to non-ADMIN callers with a public-safe DTO.
     * Kept in place for one release.
     */
    @Deprecated("Use /api/users?includePending=true once non-ADMIN access is added")
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
