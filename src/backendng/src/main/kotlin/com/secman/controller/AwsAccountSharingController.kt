package com.secman.controller

import com.secman.dto.CreateAwsAccountSharingRequest
import com.secman.service.AwsAccountSharingService
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
 * - ADMIN users: full access to all sharing rules (list all, create any, delete any)
 * - Non-admin users with AWS accounts: scoped access to their own outgoing sharing rules
 *   (list own, create where they are source, delete their own)
 */
@Controller("/api/aws-account-sharing")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class AwsAccountSharingController(
    private val awsAccountSharingService: AwsAccountSharingService,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(AwsAccountSharingController::class.java)

    /**
     * GET /api/aws-account-sharing - List sharing rules.
     * ADMINs see all rules; non-admins see only rules where they are the source user.
     */
    @Get
    fun listSharingRules(
        @QueryValue("page") page: Int?,
        @QueryValue("size") size: Int?,
        authentication: Authentication
    ): HttpResponse<Map<String, Any>> {
        logger.debug("Listing AWS account sharing rules: page=$page, size=$size")

        return try {
            val result = if (isAdmin(authentication)) {
                awsAccountSharingService.listSharingRules(page ?: 0, size ?: 20)
            } else {
                val userId = getUserIdFromAuthentication(authentication)
                awsAccountSharingService.listSharingRulesForSourceUser(userId, page ?: 0, size ?: 20)
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
     * ADMINs can create any rule; non-admins can only create rules where they are the source.
     */
    @Post
    open fun createSharingRule(
        @Valid @Body request: CreateAwsAccountSharingRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info("Creating AWS account sharing rule: source=${request.sourceUserId}, target=${request.targetUserId}")

        return try {
            val currentUserId = getUserIdFromAuthentication(authentication)

            // Non-admin users can only share their own accounts
            if (!isAdmin(authentication) && request.sourceUserId != currentUserId) {
                return HttpResponse.status<Any>(HttpStatus.FORBIDDEN).body(mapOf(
                    "error" to "Forbidden",
                    "message" to "You can only share your own AWS accounts"
                ))
            }

            val result = awsAccountSharingService.createSharingRule(request, currentUserId)
            HttpResponse.status<Any>(HttpStatus.CREATED).body(result)
        } catch (e: IllegalArgumentException) {
            logger.warn("AWS account sharing creation failed: ${e.message}")
            HttpResponse.badRequest(mapOf(
                "error" to "Validation Error",
                "message" to (e.message ?: "Invalid request")
            ))
        } catch (e: IllegalStateException) {
            logger.warn("AWS account sharing creation failed: ${e.message}")
            HttpResponse.badRequest(mapOf(
                "error" to "Conflict",
                "message" to (e.message ?: "Sharing rule already exists")
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
     * ADMINs can delete any rule; non-admins can only delete rules where they are the source.
     */
    @Delete("/{id}")
    fun deleteSharingRule(
        @PathVariable id: Long,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info("Deleting AWS account sharing rule: id=$id")

        return try {
            // Non-admin users can only delete their own rules
            if (!isAdmin(authentication)) {
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
     * Returns minimal user info (id, username, email) for populating target user dropdowns.
     * Available to all authenticated users so non-admins can select sharing targets.
     */
    @Get("/users")
    fun listUsersForSharing(): HttpResponse<List<SharingUserResponse>> {
        val users = userRepository.findAll().map { user ->
            SharingUserResponse(
                id = user.id!!,
                username = user.username,
                email = user.email
            )
        }
        return HttpResponse.ok(users)
    }

    private fun isAdmin(authentication: Authentication): Boolean {
        return authentication.roles.contains("ADMIN")
    }

    @Serdeable
    data class SharingUserResponse(
        val id: Long,
        val username: String,
        val email: String
    )
}
