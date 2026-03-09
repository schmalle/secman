package com.secman.controller

import com.secman.dto.CreateAwsAccountSharingRequest
import com.secman.service.AwsAccountSharingService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import jakarta.validation.Valid
import org.slf4j.LoggerFactory

/**
 * REST controller for AWS Account Sharing management.
 *
 * Feature: AWS Account Sharing
 *
 * All endpoints require ADMIN role.
 * Provides CRUD operations for sharing rules that allow
 * one user's AWS account visibility to be shared with another user.
 */
@Controller("/api/aws-account-sharing")
@Secured("ADMIN")
open class AwsAccountSharingController(
    private val awsAccountSharingService: AwsAccountSharingService
) {
    private val logger = LoggerFactory.getLogger(AwsAccountSharingController::class.java)

    /**
     * GET /api/aws-account-sharing - List all sharing rules with pagination.
     */
    @Get
    fun listSharingRules(
        @QueryValue("page") page: Int?,
        @QueryValue("size") size: Int?
    ): HttpResponse<Map<String, Any>> {
        logger.debug("Listing AWS account sharing rules: page=$page, size=$size")

        return try {
            val result = awsAccountSharingService.listSharingRules(page ?: 0, size ?: 20)
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
     */
    @Post
    open fun createSharingRule(
        @Valid @Body request: CreateAwsAccountSharingRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info("Creating AWS account sharing rule: source=${request.sourceUserId}, target=${request.targetUserId}")

        return try {
            val adminUserId = getUserIdFromAuthentication(authentication)
            val result = awsAccountSharingService.createSharingRule(request, adminUserId)
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
     */
    @Delete("/{id}")
    fun deleteSharingRule(@PathVariable id: Long): HttpResponse<Void> {
        logger.info("Deleting AWS account sharing rule: id=$id")

        return try {
            awsAccountSharingService.deleteSharingRule(id)
            HttpResponse.noContent()
        } catch (e: NoSuchElementException) {
            logger.warn("AWS account sharing rule not found: $id")
            HttpResponse.notFound()
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
}
