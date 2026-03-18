package com.secman.controller

import com.secman.domain.MappingStatus
import com.secman.dto.*
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
import jakarta.validation.Valid
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
open class UserMappingController(
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
    open fun createMapping(
        @Valid @Body request: CreateUserMappingRequest,
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
     * GET /api/user-mappings - List all user mappings with pagination and filters
     */
    @Get
    @Secured("ADMIN")
    fun listMappings(
        @QueryValue("page") page: Int?,
        @QueryValue("size") size: Int?,
        @QueryValue("email") email: String?,
        @QueryValue("domain") domain: String?,
        @QueryValue("awsAccountId") awsAccountId: String?,
        @QueryValue("status") status: String?
    ): HttpResponse<Map<String, Any>> {
        logger.debug("Listing user mappings: page=$page, size=$size, email=$email, domain=$domain, awsAccountId=$awsAccountId, status=$status")

        return try {
            val pageNumber = page ?: 0
            val pageSize = size ?: 20

            // Parse status filter
            val statusFilter = when (status?.uppercase()) {
                "ACTIVE" -> MappingStatus.ACTIVE
                "PENDING" -> MappingStatus.PENDING
                null, "" -> null
                else -> throw IllegalArgumentException("Invalid status filter: $status (use ACTIVE or PENDING)")
            }

            // Fetch base set based on primary filter: email > domain > awsAccountId > status > all
            var allMappings = when {
                email != null -> userMappingRepository.findByEmail(email)
                domain != null -> userMappingRepository.findByDomain(domain)
                awsAccountId != null -> userMappingRepository.findByAwsAccountId(awsAccountId)
                statusFilter != null -> userMappingRepository.findByStatus(statusFilter)
                else -> userMappingRepository.findAll().toList()
            }

            // Apply status filter as secondary filter when primary is email/domain/awsAccountId
            if (statusFilter != null && email != null || statusFilter != null && domain != null || statusFilter != null && awsAccountId != null) {
                allMappings = allMappings.filter { it.status == statusFilter }
            }

            val mappings = allMappings
                .map { it.toResponse() }
                .drop(pageNumber * pageSize)
                .take(pageSize)

            val totalElements = allMappings.size.toLong()
            val totalPages = if (pageSize > 0) ((totalElements + pageSize - 1) / pageSize).toInt() else 0

            val result = mapOf(
                "content" to mappings,
                "totalElements" to totalElements,
                "totalPages" to totalPages,
                "page" to pageNumber,
                "size" to pageSize
            )

            HttpResponse.ok(result)
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid filter parameter: ${e.message}")
            HttpResponse.badRequest(
                mapOf(
                    "error" to "Validation Error",
                    "message" to (e.message ?: "Invalid filter parameter")
                )
            )
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
     * POST /api/user-mappings/bulk - Bulk create user mappings
     *
     * Accepts a list of mapping entries and creates them in a single transaction.
     * Supports dry-run mode for comparison against existing DB state.
     * Used by CLI for import operations (CSV/JSON/S3).
     */
    @Post("/bulk")
    @Secured("ADMIN")
    open fun bulkCreateMappings(
        @Body request: BulkUserMappingRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        logger.info("Bulk create user mappings: entries=${request.mappings.size}, dryRun=${request.dryRun}")

        return try {
            val result = userMappingService.bulkCreateMappings(request)
            HttpResponse.ok(result)
        } catch (e: IllegalArgumentException) {
            logger.warn("Bulk create validation failed: ${e.message}")
            HttpResponse.badRequest(
                mapOf(
                    "error" to "Validation Error",
                    "message" to (e.message ?: "Invalid request")
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to bulk create user mappings", e)
            HttpResponse.serverError(
                mapOf(
                    "error" to "Internal Server Error",
                    "message" to "Failed to process bulk create"
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
    open fun updateMapping(
        @PathVariable id: Long,
        @Valid @Body request: UpdateUserMappingRequest,
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
     * GET /api/user-mappings/domains - Get all distinct domains
     */
    @Get("/domains")
    @Secured("ADMIN")
    fun getDistinctDomains(): HttpResponse<List<String>> {
        return HttpResponse.ok(userMappingService.getDistinctDomains())
    }

    // Feature 042: Future User Mapping Support

    /**
     * GET /api/user-mappings/current - List current mappings (future + active)
     *
     * Returns mappings that have not been applied yet (appliedAt IS NULL).
     * Includes both future user mappings and active user mappings.
     *
     * Feature: 042-future-user-mappings
     */
    @Get("/current")
    @Secured("ADMIN")
    fun getCurrentMappings(
        @QueryValue("page") page: Int?,
        @QueryValue("size") size: Int?
    ): HttpResponse<Map<String, Any>> {
        logger.debug("Listing current mappings (future + active): page=$page, size=$size")

        return try {
            val pageNumber = page ?: 0
            val pageSize = size ?: 20
            val pageable = Pageable.from(pageNumber, pageSize)

            val mappingsPage = userMappingService.getCurrentMappings(pageable)
            val totalElements = userMappingService.countCurrentMappings()

            val response = mapOf(
                "content" to mappingsPage.content,
                "totalElements" to totalElements,
                "totalPages" to mappingsPage.totalPages,
                "page" to pageNumber,
                "size" to pageSize
            )

            HttpResponse.ok(response)
        } catch (e: Exception) {
            logger.error("Failed to list current mappings", e)
            HttpResponse.serverError(
                mapOf(
                    "error" to "Internal Server Error",
                    "message" to "Failed to list current mappings"
                )
            )
        }
    }

    /**
     * GET /api/user-mappings/applied-history - List applied historical mappings
     *
     * Returns mappings that have been applied to users (appliedAt IS NOT NULL).
     * These are historical records of when future user mappings were applied.
     *
     * Feature: 042-future-user-mappings
     */
    @Get("/applied-history")
    @Secured("ADMIN")
    fun getAppliedHistory(
        @QueryValue("page") page: Int?,
        @QueryValue("size") size: Int?
    ): HttpResponse<Map<String, Any>> {
        logger.debug("Listing applied history mappings: page=$page, size=$size")

        return try {
            val pageNumber = page ?: 0
            val pageSize = size ?: 20
            val pageable = Pageable.from(pageNumber, pageSize)

            val mappingsPage = userMappingService.getAppliedHistory(pageable)
            val totalElements = userMappingService.countAppliedHistory()

            val response = mapOf(
                "content" to mappingsPage.content,
                "totalElements" to totalElements,
                "totalPages" to mappingsPage.totalPages,
                "page" to pageNumber,
                "size" to pageSize
            )

            HttpResponse.ok(response)
        } catch (e: Exception) {
            logger.error("Failed to list applied history", e)
            HttpResponse.serverError(
                mapOf(
                    "error" to "Internal Server Error",
                    "message" to "Failed to list applied history"
                )
            )
        }
    }

    /**
     * Extract user ID from authentication object
     */
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
