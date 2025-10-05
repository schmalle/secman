package com.secman.controller

import com.secman.domain.Workgroup
import com.secman.service.WorkgroupService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.transaction.Transactional
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * REST controller for Workgroup management
 * Feature: 008-create-an-additional (Workgroup-Based Access Control)
 *
 * All endpoints require ADMIN role per FR-001 through FR-012
 *
 * Endpoints:
 * - POST /api/workgroups - Create workgroup
 * - GET /api/workgroups - List all workgroups
 * - GET /api/workgroups/{id} - Get workgroup details
 * - PUT /api/workgroups/{id} - Update workgroup
 * - DELETE /api/workgroups/{id} - Delete workgroup
 * - POST /api/workgroups/{id}/users - Assign users to workgroup
 * - DELETE /api/workgroups/{workgroupId}/users/{userId} - Remove user from workgroup
 * - POST /api/workgroups/{id}/assets - Assign assets to workgroup
 * - DELETE /api/workgroups/{workgroupId}/assets/{assetId} - Remove asset from workgroup
 */
@Controller("/api/workgroups")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class WorkgroupController(
    private val workgroupService: WorkgroupService
) {

    /**
     * Create a new workgroup
     * FR-001, FR-004, FR-006: ADMIN only, unique name validation
     *
     * POST /api/workgroups
     * Body: { "name": "Engineering", "description": "Engineering team" }
     * Returns: 201 Created with workgroup object
     */
    @Post
    @Secured("ADMIN")
    open fun createWorkgroup(@Body @Valid request: CreateWorkgroupRequest): HttpResponse<Workgroup> {
        return try {
            val workgroup = workgroupService.createWorkgroup(
                name = request.name,
                description = request.description
            )
            HttpResponse.created(workgroup)
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest()
        }
    }

    /**
     * List all workgroups
     * FR-005: ADMIN only, returns all workgroups with counts
     *
     * GET /api/workgroups
     * Returns: 200 OK with array of workgroups
     */
    @Get
    @Secured("ADMIN")
    @Transactional
    open fun listWorkgroups(): HttpResponse<List<WorkgroupListResponse>> {
        val workgroups = workgroupService.listAllWorkgroups()
        val response = workgroups.map { wg ->
            WorkgroupListResponse(
                id = wg.id!!,
                name = wg.name,
                description = wg.description,
                userCount = wg.users.size,
                assetCount = wg.assets.size,
                createdAt = wg.createdAt!!,
                updatedAt = wg.updatedAt!!
            )
        }
        return HttpResponse.ok(response)
    }

    /**
     * Get workgroup details by ID
     * FR-003: ADMIN only, returns workgroup with member lists
     *
     * GET /api/workgroups/{id}
     * Returns: 200 OK with workgroup details, 404 if not found
     */
    @Get("/{id}")
    @Secured("ADMIN")
    @Transactional
    open fun getWorkgroup(@PathVariable id: Long): HttpResponse<WorkgroupDetailResponse> {
        return try {
            val workgroup = workgroupService.getWorkgroupById(id)
            val response = WorkgroupDetailResponse(
                id = workgroup.id!!,
                name = workgroup.name,
                description = workgroup.description,
                userCount = workgroup.users.size,
                assetCount = workgroup.assets.size,
                createdAt = workgroup.createdAt!!,
                updatedAt = workgroup.updatedAt!!
            )
            HttpResponse.ok(response)
        } catch (e: IllegalArgumentException) {
            HttpResponse.notFound()
        }
    }

    /**
     * Update workgroup
     * FR-002: ADMIN only, update name and/or description
     *
     * PUT /api/workgroups/{id}
     * Body: { "name": "New Name", "description": "New description" }
     * Returns: 200 OK with updated workgroup, 400 if name exists, 404 if not found
     */
    @Put("/{id}")
    @Secured("ADMIN")
    open fun updateWorkgroup(
        @PathVariable id: Long,
        @Body @Valid request: UpdateWorkgroupRequest
    ): HttpResponse<Workgroup> {
        return try {
            val workgroup = workgroupService.updateWorkgroup(
                id = id,
                name = request.name,
                description = request.description
            )
            HttpResponse.ok(workgroup)
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("not found") == true) {
                HttpResponse.notFound()
            } else {
                HttpResponse.badRequest()
            }
        }
    }

    /**
     * Delete workgroup
     * FR-003, FR-026: ADMIN only, cascade removes memberships
     *
     * DELETE /api/workgroups/{id}
     * Returns: 204 No Content, 404 if not found
     */
    @Delete("/{id}")
    @Secured("ADMIN")
    open fun deleteWorkgroup(@PathVariable id: Long): HttpResponse<Void> {
        return try {
            workgroupService.deleteWorkgroup(id)
            HttpResponse.noContent()
        } catch (e: IllegalArgumentException) {
            HttpResponse.notFound()
        }
    }

    /**
     * Assign users to workgroup
     * FR-007: ADMIN only, bulk user assignment
     *
     * POST /api/workgroups/{id}/users
     * Body: { "userIds": [1, 2, 3] }
     * Returns: 200 OK, 400 if user not found, 404 if workgroup not found
     */
    @Post("/{id}/users")
    @Secured("ADMIN")
    open fun assignUsers(
        @PathVariable id: Long,
        @Body @Valid request: AssignUsersRequest
    ): HttpResponse<Void> {
        return try {
            workgroupService.assignUsersToWorkgroup(id, request.userIds)
            HttpResponse.ok()
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("Workgroup not found") == true) {
                HttpResponse.notFound()
            } else {
                HttpResponse.badRequest()
            }
        }
    }

    /**
     * Remove user from workgroup
     * FR-008: ADMIN only, single user removal
     *
     * DELETE /api/workgroups/{workgroupId}/users/{userId}
     * Returns: 204 No Content, 404 if not found, 400 if user not in workgroup
     */
    @Delete("/{workgroupId}/users/{userId}")
    @Secured("ADMIN")
    open fun removeUser(
        @PathVariable workgroupId: Long,
        @PathVariable userId: Long
    ): HttpResponse<Void> {
        return try {
            workgroupService.removeUserFromWorkgroup(workgroupId, userId)
            HttpResponse.noContent()
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("not found") == true) {
                HttpResponse.notFound()
            } else {
                HttpResponse.badRequest()
            }
        }
    }

    /**
     * Assign assets to workgroup
     * FR-011: ADMIN only, bulk asset assignment
     *
     * POST /api/workgroups/{id}/assets
     * Body: { "assetIds": [1, 2, 3] }
     * Returns: 200 OK, 400 if asset not found, 404 if workgroup not found
     */
    @Post("/{id}/assets")
    @Secured("ADMIN")
    open fun assignAssets(
        @PathVariable id: Long,
        @Body @Valid request: AssignAssetsRequest
    ): HttpResponse<Void> {
        return try {
            workgroupService.assignAssetsToWorkgroup(id, request.assetIds)
            HttpResponse.ok()
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("Workgroup not found") == true) {
                HttpResponse.notFound()
            } else {
                HttpResponse.badRequest()
            }
        }
    }

    /**
     * Remove asset from workgroup
     * FR-012: ADMIN only, single asset removal
     *
     * DELETE /api/workgroups/{workgroupId}/assets/{assetId}
     * Returns: 204 No Content, 404 if not found, 400 if asset not in workgroup
     */
    @Delete("/{workgroupId}/assets/{assetId}")
    @Secured("ADMIN")
    open fun removeAsset(
        @PathVariable workgroupId: Long,
        @PathVariable assetId: Long
    ): HttpResponse<Void> {
        return try {
            workgroupService.removeAssetFromWorkgroup(workgroupId, assetId)
            HttpResponse.noContent()
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("not found") == true) {
                HttpResponse.notFound()
            } else {
                HttpResponse.badRequest()
            }
        }
    }
}

// Request/Response DTOs

/**
 * Request to create a workgroup
 * FR-006: Validates name format and length
 */
@Serdeable
data class CreateWorkgroupRequest(
    @field:NotBlank(message = "Workgroup name is required")
    @field:Size(min = 1, max = 100, message = "Workgroup name must be 1-100 characters")
    val name: String,

    @field:Size(max = 512, message = "Description must not exceed 512 characters")
    val description: String? = null
)

/**
 * Request to update a workgroup
 * Both fields optional, at least one should be provided
 */
@Serdeable
data class UpdateWorkgroupRequest(
    @field:Size(min = 1, max = 100, message = "Workgroup name must be 1-100 characters")
    val name: String? = null,

    @field:Size(max = 512, message = "Description must not exceed 512 characters")
    val description: String? = null
)

/**
 * Request to assign users to workgroup
 */
@Serdeable
data class AssignUsersRequest(
    @field:NotNull(message = "User IDs are required")
    val userIds: List<Long>
)

/**
 * Request to assign assets to workgroup
 */
@Serdeable
data class AssignAssetsRequest(
    @field:NotNull(message = "Asset IDs are required")
    val assetIds: List<Long>
)

/**
 * Response for workgroup list view
 * FR-005: Returns summary with counts
 */
@Serdeable
data class WorkgroupListResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val userCount: Int,
    val assetCount: Int,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant
)

/**
 * Response for workgroup detail view
 * FR-003: Returns detailed information with counts
 */
@Serdeable
data class WorkgroupDetailResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val userCount: Int,
    val assetCount: Int,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant
)
