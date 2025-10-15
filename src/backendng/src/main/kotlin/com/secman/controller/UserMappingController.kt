package com.secman.controller

import com.secman.dto.CreateUserMappingRequest
import com.secman.dto.UpdateUserMappingRequest
import com.secman.dto.UserMappingResponse
import com.secman.dto.toResponse
import com.secman.repository.UserMappingRepository
import com.secman.service.UserMappingService
import io.micronaut.data.model.Pageable
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import jakarta.inject.Inject
import org.slf4j.LoggerFactory

/**
 * REST controller for User Mapping CRUD operations
 *
 * Features:
 * - 013: User Mapping Upload (AWS accounts)
 * - 020: IP Address Mapping (IP addresses, CIDR, dash ranges)
 *
 * Authorization: ADMIN role required for all operations
 */
@Controller("/api/user-mappings")
@Secured(SecurityRule.IS_AUTHENTICATED)
class UserMappingController(
    @Inject private val userMappingService: UserMappingService,
    @Inject private val userMappingRepository: UserMappingRepository
) {
    private val logger = LoggerFactory.getLogger(UserMappingController::class.java)

    /**
     * POST /api/user-mappings - Create new user mapping
     * Supports AWS account, IP address, or both
     */
    @Post
    @Secured("ADMIN")
    fun createMapping(
        @Body request: CreateUserMappingRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info("Creating user mapping: email=${request.email}, aws=${request.awsAccountId}, ip=${request.ipAddress}")

        return try {
            val userId = getUserIdFromAuthentication(authentication)
            val result = userMappingService.createMapping(userId, request)

            HttpResponse.status<UserMappingResponse>(HttpStatus.CREATED)
                .body(result)
        } catch (e: IllegalArgumentException) {
            logger.warn("User mapping creation failed: ${e.message}")
            HttpResponse.badRequest(
                mapOf(
                    "error" to "Validation Error",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: IllegalStateException) {
            logger.warn("User mapping creation failed: ${e.message}")
            HttpResponse.badRequest(
                mapOf(
                    "error" to "Conflict",
                    "message" to (e.message ?: "Mapping already exists")
                )
            )
        }
    }

    /**
     * GET /api/user-mappings - List all user mappings with pagination
     */
    @Get
    @Secured("ADMIN")
    fun listMappings(
        @QueryValue("page") page: Int?,
        @QueryValue("size") size: Int?,
        @QueryValue("email") email: String?,
        @QueryValue("domain") domain: String?
    ): HttpResponse<Map<String, Any>> {
        logger.debug("Listing user mappings: page=$page, size=$size, email=$email, domain=$domain")

        return try {
            val pageNumber = page ?: 0
            val pageSize = size ?: 20
            val pageable = Pageable.from(pageNumber, pageSize)

            val mappingsPage = if (email != null) {
                // Filter by email
                val mappings = userMappingRepository.findByEmail(email)
                    .map { it.toResponse() }
                    .drop(pageNumber * pageSize)
                    .take(pageSize)

                val totalElements = userMappingRepository.findByEmail(email).size.toLong()
                val totalPages = ((totalElements + pageSize - 1) / pageSize).toInt()

                mapOf(
                    "content" to mappings,
                    "totalElements" to totalElements,
                    "totalPages" to totalPages,
                    "page" to pageNumber,
                    "size" to pageSize
                )
            } else if (domain != null) {
                // Filter by domain
                val mappings = userMappingRepository.findByDomain(domain)
                    .map { it.toResponse() }
                    .drop(pageNumber * pageSize)
                    .take(pageSize)

                val totalElements = userMappingRepository.findByDomain(domain).size.toLong()
                val totalPages = ((totalElements + pageSize - 1) / pageSize).toInt()

                mapOf(
                    "content" to mappings,
                    "totalElements" to totalElements,
                    "totalPages" to totalPages,
                    "page" to pageNumber,
                    "size" to pageSize
                )
            } else {
                // Get all mappings
                val allMappings = userMappingRepository.findAll()
                    .map { it.toResponse() }

                val mappings = allMappings
                    .drop(pageNumber * pageSize)
                    .take(pageSize)

                val totalElements = allMappings.size.toLong()
                val totalPages = ((totalElements + pageSize - 1) / pageSize).toInt()

                mapOf(
                    "content" to mappings,
                    "totalElements" to totalElements,
                    "totalPages" to totalPages,
                    "page" to pageNumber,
                    "size" to pageSize
                )
            }

            HttpResponse.ok(mappingsPage)
        } catch (e: Exception) {
            logger.error("Failed to list user mappings", e)
            HttpResponse.serverError(
                mapOf(
                    "error" to "Internal Server Error",
                    "message" to "Failed to list user mappings"
                )
            )
        }
    }

    /**
     * GET /api/user-mappings/{id} - Get user mapping by ID
     */
    @Get("/{id}")
    @Secured("ADMIN")
    fun getMappingById(@PathVariable id: Long): HttpResponse<*> {
        logger.debug("Getting user mapping by ID: $id")

        return try {
            val mapping = userMappingRepository.findById(id)
                .orElseThrow { NoSuchElementException("Mapping not found") }

            HttpResponse.ok(mapping.toResponse())
        } catch (e: NoSuchElementException) {
            logger.warn("User mapping not found: $id")
            HttpResponse.notFound(
                mapOf(
                    "error" to "Not Found",
                    "message" to (e.message ?: "Mapping not found")
                )
            )
        }
    }

    /**
     * PUT /api/user-mappings/{id} - Update user mapping
     */
    @Put("/{id}")
    @Secured("ADMIN")
    fun updateMapping(
        @PathVariable id: Long,
        @Body request: UpdateUserMappingRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info("Updating user mapping: id=$id, email=${request.email}, aws=${request.awsAccountId}, ip=${request.ipAddress}")

        return try {
            val userId = getUserIdFromAuthentication(authentication)
            val result = userMappingService.updateMapping(userId, id, request)

            HttpResponse.ok(result)
        } catch (e: NoSuchElementException) {
            logger.warn("User mapping not found for update: $id")
            HttpResponse.notFound(
                mapOf(
                    "error" to "Not Found",
                    "message" to (e.message ?: "Mapping not found")
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.warn("User mapping update failed: ${e.message}")
            HttpResponse.badRequest(
                mapOf(
                    "error" to "Validation Error",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: IllegalStateException) {
            logger.warn("User mapping update failed: ${e.message}")
            HttpResponse.badRequest(
                mapOf(
                    "error" to "Conflict",
                    "message" to (e.message ?: "Mapping already exists")
                )
            )
        }
    }

    /**
     * DELETE /api/user-mappings/{id} - Delete user mapping
     */
    @Delete("/{id}")
    @Secured("ADMIN")
    fun deleteMapping(
        @PathVariable id: Long,
        authentication: Authentication
    ): HttpResponse<Void> {
        logger.info("Deleting user mapping: id=$id")

        return try {
            val userId = getUserIdFromAuthentication(authentication)
            userMappingService.deleteMapping(userId, id)

            HttpResponse.noContent()
        } catch (e: NoSuchElementException) {
            logger.warn("User mapping not found for deletion: $id")
            HttpResponse.notFound()
        }
    }

    /**
     * Extract user ID from authentication object
     */
    private fun getUserIdFromAuthentication(authentication: Authentication): Long {
        val userId = authentication.attributes["id"]
        return when (userId) {
            is Long -> userId
            is Int -> userId.toLong()
            is String -> userId.toLong()
            else -> throw IllegalStateException("Unable to determine user ID from authentication")
        }
    }
}
