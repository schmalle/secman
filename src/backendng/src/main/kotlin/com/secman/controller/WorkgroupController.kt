package com.secman.controller

import com.secman.domain.Criticality
import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.dto.BreadcrumbItem
import com.secman.dto.CreateChildWorkgroupRequest
import com.secman.dto.WorkgroupResponse
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import com.secman.service.ValidationException
import com.secman.service.UserResolutionService
import com.secman.service.WorkgroupService
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.transaction.Transactional
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory

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
    private val workgroupService: WorkgroupService,
    private val assetRepository: AssetRepository,
    private val workgroupAwsAccountRepository: com.secman.repository.WorkgroupAwsAccountRepository,
    private val userResolutionService: UserResolutionService,
    private val workgroupRepository: WorkgroupRepository,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(WorkgroupController::class.java)

    /** True when the caller has the ADMIN role. */
    private fun isAdmin(authentication: Authentication): Boolean =
        authentication.roles.contains("ADMIN")

    /**
     * Resolve the calling User from the Authentication principal.
     * @throws IllegalStateException if no matching user row exists (should not happen for an authenticated request).
     */
    private fun currentUser(authentication: Authentication): User =
        userRepository.findByUsername(authentication.name).orElseThrow {
            IllegalStateException("Authenticated user not found: ${authentication.name}")
        }

    /**
     * Member-or-admin authorization for workgroup-scoped actions.
     * Caller must already be inside a transaction so the LAZY users collection can be read.
     */
    private fun isMemberOrAdmin(workgroup: Workgroup, authentication: Authentication): Boolean {
        if (isAdmin(authentication)) return true
        val user = currentUser(authentication)
        return workgroup.users.any { it.id == user.id }
    }

    /**
     * Returns the IDs of workgroups the caller is allowed to see, or null when the
     * caller is privileged enough to see everything (ADMIN or SECCHAMPION). Mirrors
     * the gating used by the flat list endpoint so the tree-view endpoints agree
     * with `GET /api/workgroups` on which workgroups exist from the caller's POV.
     *
     * Returning null (instead of "all ids") avoids a second DB round-trip for the
     * common admin path and lets callers branch on `== null` for "no filter".
     */
    private fun accessibleWorkgroupIdsOrNull(authentication: Authentication): Set<Long>? {
        if (isAdmin(authentication) || authentication.roles.contains("SECCHAMPION")) {
            return null
        }
        val user = currentUser(authentication)
        return workgroupRepository.findWorkgroupsByUserEmail(user.email)
            .mapNotNull { it.id }
            .toSet()
    }

    /**
     * Create a new workgroup
     * FR-001, FR-004, FR-006: ADMIN only, unique name validation
     *
     * POST /api/workgroups
     * Body: { "name": "Engineering", "description": "Engineering team" }
     * Returns: 201 Created with workgroup object
     */
    @Post
    @Secured(SecurityRule.IS_AUTHENTICATED)
    open fun createWorkgroup(
        @Body @Valid request: CreateWorkgroupRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            val criticality = request.criticality ?: Criticality.MEDIUM
            val workgroup = if (isAdmin(authentication)) {
                workgroupService.createWorkgroup(
                    name = request.name,
                    description = request.description,
                    criticality = criticality
                )
            } else {
                // Non-admin creators are auto-enrolled so they can subsequently
                // edit/delete the workgroup under member-driven authorization.
                val creator = currentUser(authentication)
                workgroupService.createWorkgroupWithCreator(
                    name = request.name,
                    description = request.description,
                    criticality = criticality,
                    creatorUserId = creator.id!!
                )
            }
            HttpResponse.created(workgroup)
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid workgroup data")))
        } catch (e: ConstraintViolationException) {
            HttpResponse.badRequest(mapOf("error" to formatViolations(e)))
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
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun listWorkgroups(authentication: Authentication): HttpResponse<List<WorkgroupListResponse>> {
        // ADMIN and SECCHAMPION see every workgroup; regular users see only the
        // workgroups they are members of (privacy default — see /workgroups UX).
        val workgroups = if (isAdmin(authentication) || authentication.roles.contains("SECCHAMPION")) {
            workgroupService.listAllWorkgroups()
        } else {
            val user = currentUser(authentication)
            workgroupRepository.findWorkgroupsByUserEmail(user.email)
        }
        val response = workgroups.map { wg ->
            val ancestors = wg.getAncestors()
            WorkgroupListResponse(
                id = wg.id!!,
                name = wg.name,
                description = wg.description,
                criticality = wg.criticality,
                userCount = wg.users.size,
                assetCount = wg.assets.size,
                awsAccountsCount = workgroupAwsAccountRepository.countByWorkgroupId(wg.id!!),
                createdAt = wg.createdAt!!,
                updatedAt = wg.updatedAt!!,
                parentId = wg.parent?.id,
                parentName = wg.parent?.name,
                depth = wg.calculateDepth(),
                ancestors = ancestors.map { ancestor ->
                    BreadcrumbItem(id = ancestor.id!!, name = ancestor.name)
                }
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
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun getWorkgroup(
        @PathVariable id: Long,
        authentication: Authentication
    ): HttpResponse<WorkgroupDetailResponse> {
        return try {
            val workgroup = workgroupService.getWorkgroupById(id)
            if (!isMemberOrAdmin(workgroup, authentication)) {
                return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
            }
            val response = WorkgroupDetailResponse(
                id = workgroup.id!!,
                name = workgroup.name,
                description = workgroup.description,
                criticality = workgroup.criticality,
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
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun updateWorkgroup(
        @PathVariable id: Long,
        @Body @Valid request: UpdateWorkgroupRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            val existing = workgroupService.getWorkgroupById(id)
            if (!isMemberOrAdmin(existing, authentication)) {
                return HttpResponse.status<Any>(io.micronaut.http.HttpStatus.FORBIDDEN)
            }
            val workgroup = workgroupService.updateWorkgroup(
                id = id,
                name = request.name,
                description = request.description,
                criticality = request.criticality
            )
            HttpResponse.ok(workgroup)
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("not found") == true) {
                HttpResponse.notFound()
            } else {
                HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid workgroup data")))
            }
        } catch (e: ConstraintViolationException) {
            HttpResponse.badRequest(mapOf("error" to formatViolations(e)))
        }
    }

    /**
     * Delete workgroup with child promotion
     * Feature 040: Nested Workgroups (User Story 4)
     * FR-003, FR-026: ADMIN only, promotes children to grandparent before deletion
     *
     * DELETE /api/workgroups/{id}
     * Returns: 204 No Content, 404 if not found
     */
    @Delete("/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun deleteWorkgroup(
        @PathVariable id: Long,
        authentication: Authentication
    ): HttpResponse<Void> {
        return try {
            val workgroup = workgroupService.getWorkgroupById(id)
            if (isAdmin(authentication)) {
                // Admin retains the legacy "promote children to grandparent" semantics
                // so hierarchy continuity is preserved across organizational re-shuffles.
                workgroupService.deleteWorkgroupWithPromotion(id)
                return HttpResponse.noContent()
            }
            if (!isMemberOrAdmin(workgroup, authentication)) {
                return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
            }
            // Non-admin members may not delete a parent workgroup — promoting children
            // could re-parent them under a workgroup the deleter cannot see.
            if (workgroup.children.isNotEmpty()) {
                return HttpResponse.badRequest()
            }
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
     * Body: { "userIds": [1, 2, 3] }                  // legacy id-only shape, still supported
     *   or  { "userRefs": [{"id": 1}, {"email": "x@y"}] }  // new shape; pending emails lazy-create User rows
     * Returns: 200 OK, 400 if request empty, 404 if workgroup or user-by-id not found
     */
    @Post("/{id}/users")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun assignUsers(
        @PathVariable id: Long,
        @Body @Valid request: AssignUsersRequest,
        authentication: Authentication
    ): HttpResponse<Void> {
        return try {
            if (request.isEmpty()) {
                return HttpResponse.badRequest()
            }

            // Validate workgroup BEFORE resolving refs — UserResolutionService.resolveAll
            // is @Transactional and lazy-creates User rows on its own. If we resolved
            // first and the workgroup turned out to be missing, the new User rows
            // would already be committed and orphaned.
            val workgroup = workgroupRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound()
            if (!isMemberOrAdmin(workgroup, authentication)) {
                return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
            }

            // userRefs wins when both shapes are present.
            val resolvedIds: List<Long> = if (!request.userRefs.isNullOrEmpty()) {
                userResolutionService
                    .resolveAll(request.userRefs, "workgroup member")
                    .map { it.id!! }
            } else {
                request.userIds!!
            }

            workgroupService.assignUsersToWorkgroup(id, resolvedIds)
            HttpResponse.ok()
        } catch (e: NoSuchElementException) {
            HttpResponse.notFound()
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("Workgroup not found") == true) {
                HttpResponse.notFound()
            } else {
                HttpResponse.badRequest()
            }
        }
    }

    /**
     * List the users currently assigned to a workgroup. Member-or-admin only.
     *
     * GET /api/workgroups/{id}/users
     * Returns: 200 OK with [{id, username, email}], 403 if caller not a member, 404 if missing.
     */
    @Get("/{id}/users")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun listAssignedUsers(
        @PathVariable id: Long,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            val workgroup = workgroupService.getWorkgroupById(id)
            if (!isMemberOrAdmin(workgroup, authentication)) {
                return HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.FORBIDDEN)
                    .body(mapOf("error" to "You must be a member of '${workgroup.name}' to view its members"))
            }
            val members = workgroup.users
                .map { AssignedUserDto(id = it.id!!, username = it.username, email = it.email) }
                .sortedBy { it.username.lowercase() }
            HttpResponse.ok(members)
        } catch (e: IllegalArgumentException) {
            HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.NOT_FOUND)
                .body(mapOf("error" to (e.message ?: "Workgroup not found")))
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
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun removeUser(
        @PathVariable workgroupId: Long,
        @PathVariable userId: Long,
        authentication: Authentication
    ): HttpResponse<Void> {
        return try {
            val workgroup = workgroupRepository.findById(workgroupId).orElse(null)
                ?: return HttpResponse.notFound()
            if (!isMemberOrAdmin(workgroup, authentication)) {
                return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
            }
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
     * Bulk-remove users from a workgroup. Member-or-admin. Mirrors POST /{id}/users
     * so the "Assign Users" modal can apply pending removals in one round-trip.
     *
     * DELETE /api/workgroups/{id}/users
     * Body: { "userIds": [1, 2, 3] }
     * Returns: 204 No Content, 400 if body empty/missing userIds, 404 if workgroup or any user not found.
     * Idempotent: ids not currently in the workgroup are silently skipped.
     */
    @Delete("/{id}/users")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun removeUsers(
        @PathVariable id: Long,
        @Body @Valid request: RemoveUsersRequest,
        authentication: Authentication
    ): HttpResponse<Void> {
        return try {
            if (request.userIds.isEmpty()) {
                return HttpResponse.badRequest()
            }
            val workgroup = workgroupRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound()
            if (!isMemberOrAdmin(workgroup, authentication)) {
                return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
            }
            val removedCount = workgroupService.removeUsersFromWorkgroup(id, request.userIds)
            logger.info(
                "AUDIT: operation=REMOVE_USERS_BULK, actor={}, workgroup={}, requested={}, removed={}",
                authentication.name, workgroup.name, request.userIds.size, removedCount
            )
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
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun assignAssets(
        @PathVariable id: Long,
        @Body @Valid request: AssignAssetsRequest,
        authentication: Authentication
    ): HttpResponse<Void> {
        return try {
            val workgroup = workgroupRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound()
            if (!isMemberOrAdmin(workgroup, authentication)) {
                return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
            }
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
     * List assets currently assigned to a workgroup. Member-or-admin only.
     * Companion to POST /{id}/assets so the "Manage Assets" UI can render a
     * "Currently assigned" panel without requiring the ADMIN-only /cli/ endpoint.
     *
     * GET /api/workgroups/{id}/assets
     * Returns: 200 OK with [{id, name, type, ip, owner}], 403 if caller not a member, 404 if missing.
     */
    @Get("/{id}/assets")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun listAssignedAssets(
        @PathVariable id: Long,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            val workgroup = workgroupService.getWorkgroupById(id)
            if (!isMemberOrAdmin(workgroup, authentication)) {
                return HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.FORBIDDEN)
                    .body(mapOf("error" to "You must be a member of '${workgroup.name}' to view its assets"))
            }
            val assets = assetRepository.findByWorkgroupsIdOrderByNameAsc(id).map {
                AssignedAssetDto(
                    id = it.id!!,
                    name = it.name,
                    type = it.type,
                    ip = it.ip,
                    owner = it.owner
                )
            }
            HttpResponse.ok(assets)
        } catch (e: IllegalArgumentException) {
            HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.NOT_FOUND)
                .body(mapOf("error" to (e.message ?: "Workgroup not found")))
        }
    }

    /**
     * Bulk-remove assets from a workgroup. Member-or-admin. Mirrors POST /{id}/assets
     * so the "Manage Assets" modal can apply pending removals in one round-trip.
     *
     * DELETE /api/workgroups/{id}/assets
     * Body: { "assetIds": [1, 2, 3] }
     * Returns: 204 No Content, 400 if body empty/missing assetIds, 404 if workgroup not found.
     * Idempotent: ids not currently in the workgroup are silently skipped.
     */
    @Delete("/{id}/assets")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun removeAssets(
        @PathVariable id: Long,
        @Body @Valid request: AssignAssetsRequest,
        authentication: Authentication
    ): HttpResponse<Void> {
        return try {
            if (request.assetIds.isEmpty()) {
                return HttpResponse.badRequest()
            }
            val workgroup = workgroupRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound()
            if (!isMemberOrAdmin(workgroup, authentication)) {
                return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
            }
            val removedCount = workgroupService.removeAssetsFromWorkgroup(id, request.assetIds)
            logger.info(
                "AUDIT: operation=REMOVE_ASSETS_BULK, actor={}, workgroup={}, requested={}, removed={}",
                authentication.name, workgroup.name, request.assetIds.size, removedCount
            )
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
     * Remove asset from workgroup
     * FR-012: ADMIN only, single asset removal
     *
     * DELETE /api/workgroups/{workgroupId}/assets/{assetId}
     * Returns: 204 No Content, 404 if not found, 400 if asset not in workgroup
     */
    @Delete("/{workgroupId}/assets/{assetId}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun removeAsset(
        @PathVariable workgroupId: Long,
        @PathVariable assetId: Long,
        authentication: Authentication
    ): HttpResponse<Void> {
        return try {
            val workgroup = workgroupRepository.findById(workgroupId).orElse(null)
                ?: return HttpResponse.notFound()
            if (!isMemberOrAdmin(workgroup, authentication)) {
                return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
            }
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

    // Feature 040: Nested Workgroups - Hierarchy Endpoints

    /**
     * Create a child workgroup under a parent
     * Feature 040: Nested Workgroups (User Story 1)
     *
     * POST /api/workgroups/{id}/children
     * Body: { "name": "Engineering", "description": "Engineering team" }
     * Returns: 201 Created with child workgroup, 400 if validation fails, 404 if parent not found
     */
    @Post("/{id}/children")
    @Secured("ADMIN")
    @Transactional
    open fun createChildWorkgroup(
        @PathVariable id: Long,
        @Body @Valid request: CreateChildWorkgroupRequest
    ): HttpResponse<*> {
        return try {
            val child = workgroupService.createChildWorkgroup(
                parentId = id,
                name = request.name,
                description = request.description
            )
            HttpResponse.created(toWorkgroupResponse(child))
        } catch (e: IllegalArgumentException) {
            HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.NOT_FOUND)
                .body(mapOf("error" to (e.message ?: "Parent workgroup not found")))
        } catch (e: ValidationException) {
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Validation failed")))
        }
    }

    /**
     * Get all direct children of a workgroup
     * Feature 040: Nested Workgroups (User Story 2)
     *
     * GET /api/workgroups/{id}/children
     * Returns: 200 OK with list of child workgroups, 404 if parent not found
     */
    @Get("/{id}/children")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun getChildren(
        @PathVariable id: Long,
        authentication: Authentication
    ): HttpResponse<List<WorkgroupResponse>> {
        return try {
            // Tree-view consistency: non-privileged callers must not see children
            // of a workgroup they can't access, nor children outside their accessible
            // set. Return 404 (not 403) so we don't leak the existence of hidden
            // parents — matches the posture of the flat list endpoint.
            val accessibleIds = accessibleWorkgroupIdsOrNull(authentication)
            if (accessibleIds != null && id !in accessibleIds) {
                return HttpResponse.notFound()
            }
            val children = workgroupService.getChildren(id)
            val filtered = if (accessibleIds == null) {
                children
            } else {
                children.filter { it.id in accessibleIds }
            }
            HttpResponse.ok(filtered.map { toWorkgroupResponse(it) })
        } catch (e: IllegalArgumentException) {
            HttpResponse.notFound()
        }
    }

    /**
     * Get all root-level workgroups (no parent)
     * Feature 040: Nested Workgroups (User Story 2)
     *
     * GET /api/workgroups/root
     * Returns: 200 OK with list of root-level workgroups
     */
    @Get("/root")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun getRootWorkgroups(authentication: Authentication): HttpResponse<List<WorkgroupResponse>> {
        val accessibleIds = accessibleWorkgroupIdsOrNull(authentication)
        val roots = if (accessibleIds == null) {
            // ADMIN / SECCHAMPION see the real top-level tree.
            workgroupService.getRootWorkgroups()
        } else {
            // For a restricted user, "root" means "as far up the tree as they can see".
            // An accessible workgroup whose parent is null OR not accessible is a
            // visible-root — that way a member of a deep workgroup whose ancestors
            // are hidden still gets an entry point into the tree.
            val all = workgroupService.listAllWorkgroups()
            all.filter {
                it.id in accessibleIds &&
                    (it.parent == null || it.parent?.id !in accessibleIds)
            }
        }
        return HttpResponse.ok(roots.map { toWorkgroupResponse(it) })
    }

    /**
     * Get all ancestors from root to immediate parent
     * Feature 040: Nested Workgroups (User Story 5)
     *
     * GET /api/workgroups/{id}/ancestors
     * Returns: 200 OK with list of ancestors (root first), 404 if not found
     */
    @Get("/{id}/ancestors")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun getAncestors(@PathVariable id: Long): HttpResponse<List<BreadcrumbItem>> {
        return try {
            val ancestors = workgroupService.getAncestors(id)
            val response = ancestors.map { BreadcrumbItem(id = it.id!!, name = it.name) }
            HttpResponse.ok(response)
        } catch (e: IllegalArgumentException) {
            HttpResponse.notFound()
        }
    }

    /**
     * Get all descendants (entire subtree)
     * Feature 040: Nested Workgroups (User Story 2)
     *
     * GET /api/workgroups/{id}/descendants
     * Returns: 200 OK with list of all descendants
     */
    @Get("/{id}/descendants")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun getDescendants(@PathVariable id: Long): HttpResponse<List<WorkgroupResponse>> {
        val descendants = workgroupService.getDescendants(id)
        val response = descendants.map { toWorkgroupResponse(it) }
        return HttpResponse.ok(response)
    }

    /**
     * Move a workgroup to a new parent.
     *
     * Authorization: caller must be a member of (or ADMIN for) the workgroup being moved,
     * AND — if a non-null newParentId is given — also a member of (or ADMIN for) the new
     * parent. Moving a workgroup under a parent the caller cannot see would let a member
     * silently re-organize someone else's tree, so the new-parent check is non-negotiable.
     * Moves to root level (newParentId == null) require only source membership.
     *
     * PUT /api/workgroups/{id}/parent
     * Body: { "newParentId": 123 } or { "newParentId": null } for root level
     * Returns: 200 OK with updated workgroup, 400 if validation fails, 403 if not authorized,
     *          404 if workgroup or parent not found.
     */
    @Put("/{id}/parent")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    open fun moveWorkgroup(
        @PathVariable id: Long,
        @Body @Valid request: com.secman.dto.MoveWorkgroupRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            val workgroup = workgroupService.getWorkgroupById(id)
            if (!isMemberOrAdmin(workgroup, authentication)) {
                return HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.FORBIDDEN)
                    .body(mapOf("error" to "You must be a member of '${workgroup.name}' to move it"))
            }
            val newParentId = request.newParentId
            if (newParentId != null) {
                val newParent = workgroupService.getWorkgroupById(newParentId)
                if (!isMemberOrAdmin(newParent, authentication)) {
                    return HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.FORBIDDEN)
                        .body(mapOf("error" to "You must be a member of the target parent '${newParent.name}' to move a workgroup under it"))
                }
            }
            val moved = workgroupService.moveWorkgroup(id, newParentId)
            HttpResponse.ok(toWorkgroupResponse(moved))
        } catch (e: IllegalArgumentException) {
            HttpResponse.status<Map<String, String>>(io.micronaut.http.HttpStatus.NOT_FOUND)
                .body(mapOf("error" to (e.message ?: "Workgroup not found")))
        } catch (e: ValidationException) {
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Move not allowed")))
        }
    }

    // --- CLI-specific endpoints for workgroup management without direct DB access ---

    @Serdeable
    data class CliAssetDto(
        val id: Long,
        val name: String,
        val type: String?,
        val ip: String?,
        val owner: String?
    )

    @Serdeable
    data class PatternOperationRequest(
        val pattern: String? = null,
        val type: String? = null,
        val assetIds: List<Long>? = null,
        val dryRun: Boolean = false
    )

    @Serdeable
    data class WorkgroupOperationResultDto(
        val success: Boolean,
        val message: String,
        val assigned: Int = 0,
        val removed: Int = 0,
        val skipped: Int = 0,
        val errors: List<String> = emptyList(),
        val assetNames: List<String> = emptyList()
    )

    /**
     * GET /api/workgroups/cli/search-assets
     *
     * Search assets by wildcard pattern and optional type filter.
     * Used by CLI for pattern-based asset operations.
     */
    @Get("/cli/search-assets")
    @Secured("ADMIN")
    @Transactional
    open fun searchAssets(
        @QueryValue(defaultValue = "") pattern: String,
        @QueryValue(defaultValue = "") type: String
    ): HttpResponse<List<CliAssetDto>> {
        val allAssets = assetRepository.findAll().toList()

        var filtered = allAssets

        if (type.isNotBlank()) {
            filtered = filtered.filter { it.type.equals(type, ignoreCase = true) }
        }

        if (pattern.isNotBlank()) {
            val regex = wildcardToRegex(pattern.lowercase())
            filtered = filtered.filter { regex.matches(it.name.lowercase()) }
        }

        val result = filtered.sortedBy { it.name.lowercase() }.map {
            CliAssetDto(
                id = it.id!!,
                name = it.name,
                type = it.type,
                ip = it.ip,
                owner = it.owner
            )
        }

        return HttpResponse.ok(result)
    }

    /**
     * GET /api/workgroups/{id}/cli/assets
     *
     * List assets in a workgroup. Used by CLI for listing workgroup contents.
     */
    @Get("/{id}/cli/assets")
    @Secured("ADMIN")
    @Transactional
    open fun listWorkgroupAssets(@PathVariable id: Long): HttpResponse<List<CliAssetDto>> {
        return try {
            workgroupService.getWorkgroupById(id)
            val assets = assetRepository.findByWorkgroupsIdOrderByNameAsc(id)
            val result = assets.map {
                CliAssetDto(
                    id = it.id!!,
                    name = it.name,
                    type = it.type,
                    ip = it.ip,
                    owner = it.owner
                )
            }
            HttpResponse.ok(result)
        } catch (e: IllegalArgumentException) {
            HttpResponse.notFound()
        }
    }

    /**
     * POST /api/workgroups/{id}/cli/assign-assets
     *
     * Assign assets to a workgroup by IDs or pattern. Used by CLI.
     */
    @Post("/{id}/cli/assign-assets")
    @Secured("ADMIN")
    @Transactional
    open fun cliAssignAssets(
        @PathVariable id: Long,
        @Body request: PatternOperationRequest,
        authentication: Authentication
    ): HttpResponse<WorkgroupOperationResultDto> {
        val adminEmail = authentication.name

        return try {
            val workgroup = workgroupService.getWorkgroupById(id)

            // Resolve asset IDs from pattern or direct IDs
            val assetIds = resolveAssetIds(request)
            if (assetIds.isEmpty()) {
                return HttpResponse.ok(WorkgroupOperationResultDto(
                    success = true,
                    message = "No assets found matching criteria",
                    assigned = 0, skipped = 0
                ))
            }

            if (request.dryRun) {
                val assets = assetIds.mapNotNull { assetRepository.findById(it).orElse(null) }
                return HttpResponse.ok(WorkgroupOperationResultDto(
                    success = true,
                    message = "Dry run: would assign ${assets.size} assets to workgroup '${workgroup.name}'",
                    assigned = assets.size,
                    skipped = 0,
                    assetNames = assets.map { it.name }
                ))
            }

            var assigned = 0
            var skipped = 0
            val errors = mutableListOf<String>()
            val assignedNames = mutableListOf<String>()

            for (assetId in assetIds) {
                val asset = assetRepository.findById(assetId).orElse(null)
                if (asset == null) {
                    errors.add("Asset not found with ID: $assetId")
                    continue
                }
                if (asset.workgroups.any { it.id == id }) {
                    skipped++
                    continue
                }
                asset.workgroups.add(workgroup)
                assetRepository.update(asset)
                assigned++
                assignedNames.add(asset.name)
                logger.info("AUDIT: operation=ASSIGN_ASSET, actor={}, workgroup={}, asset={}",
                    adminEmail, workgroup.name, asset.name)
            }

            HttpResponse.ok(WorkgroupOperationResultDto(
                success = errors.isEmpty(),
                message = "Assigned $assigned assets to workgroup '${workgroup.name}' (skipped $skipped already assigned)",
                assigned = assigned,
                skipped = skipped,
                errors = errors,
                assetNames = assignedNames
            ))
        } catch (e: IllegalArgumentException) {
            HttpResponse.notFound()
        }
    }

    /**
     * POST /api/workgroups/{id}/cli/remove-assets
     *
     * Remove assets from a workgroup by IDs, pattern, or all.
     */
    @Post("/{id}/cli/remove-assets")
    @Secured("ADMIN")
    @Transactional
    open fun cliRemoveAssets(
        @PathVariable id: Long,
        @Body request: PatternOperationRequest,
        authentication: Authentication
    ): HttpResponse<WorkgroupOperationResultDto> {
        val adminEmail = authentication.name

        return try {
            val workgroup = workgroupService.getWorkgroupById(id)
            val assetsInWorkgroup = assetRepository.findByWorkgroupsIdOrderByNameAsc(id)

            // If no assetIds and no pattern, remove all
            val assetIds = if (request.assetIds.isNullOrEmpty() && request.pattern.isNullOrBlank()) {
                assetsInWorkgroup.mapNotNull { it.id }
            } else if (!request.pattern.isNullOrBlank()) {
                // Filter by pattern within workgroup
                val regex = wildcardToRegex(request.pattern.lowercase())
                var matching = assetsInWorkgroup.filter { regex.matches(it.name.lowercase()) }
                if (!request.type.isNullOrBlank()) {
                    matching = matching.filter { it.type.equals(request.type, ignoreCase = true) }
                }
                matching.mapNotNull { it.id }
            } else {
                request.assetIds ?: emptyList()
            }

            if (assetIds.isEmpty()) {
                return HttpResponse.ok(WorkgroupOperationResultDto(
                    success = true,
                    message = "No assets found matching criteria",
                    removed = 0, skipped = 0
                ))
            }

            if (request.dryRun) {
                val assets = assetIds.mapNotNull { aid -> assetsInWorkgroup.find { it.id == aid } }
                return HttpResponse.ok(WorkgroupOperationResultDto(
                    success = true,
                    message = "Dry run: would remove ${assets.size} assets from workgroup '${workgroup.name}'",
                    removed = assets.size,
                    skipped = 0,
                    assetNames = assets.map { it.name }
                ))
            }

            var removed = 0
            var skipped = 0
            val errors = mutableListOf<String>()
            val removedNames = mutableListOf<String>()

            for (assetId in assetIds) {
                val asset = assetRepository.findById(assetId).orElse(null)
                if (asset == null) {
                    errors.add("Asset not found with ID: $assetId")
                    continue
                }
                if (!asset.workgroups.any { it.id == id }) {
                    skipped++
                    continue
                }
                asset.workgroups.removeIf { it.id == id }
                assetRepository.update(asset)
                removed++
                removedNames.add(asset.name)
                logger.info("AUDIT: operation=REMOVE_ASSET, actor={}, workgroup={}, asset={}",
                    adminEmail, workgroup.name, asset.name)
            }

            HttpResponse.ok(WorkgroupOperationResultDto(
                success = errors.isEmpty(),
                message = "Removed $removed assets from workgroup '${workgroup.name}' (skipped $skipped not assigned)",
                removed = removed,
                skipped = skipped,
                errors = errors,
                assetNames = removedNames
            ))
        } catch (e: IllegalArgumentException) {
            HttpResponse.notFound()
        }
    }

    private fun resolveAssetIds(request: PatternOperationRequest): List<Long> {
        if (!request.assetIds.isNullOrEmpty()) {
            return request.assetIds
        }
        if (!request.pattern.isNullOrBlank()) {
            val allAssets = assetRepository.findAll().toList()
            val regex = wildcardToRegex(request.pattern.lowercase())
            var matching = allAssets.filter { regex.matches(it.name.lowercase()) }
            if (!request.type.isNullOrBlank()) {
                matching = matching.filter { it.type.equals(request.type, ignoreCase = true) }
            }
            return matching.mapNotNull { it.id }
        }
        return emptyList()
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val regexPattern = pattern
            .replace("\\", "\\\\")
            .replace(".", "\\.")
            .replace("+", "\\+")
            .replace("^", "\\^")
            .replace("$", "\\$")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("|", "\\|")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("*", ".*")
            .replace("?", ".")
        return Regex("^$regexPattern$")
    }

    /**
     * Helper method to convert Workgroup entity to WorkgroupResponse DTO
     * Feature 040: Nested Workgroups
     */
    private fun toWorkgroupResponse(workgroup: Workgroup): WorkgroupResponse {
        val ancestors = workgroup.getAncestors().map { ancestor ->
            BreadcrumbItem(id = ancestor.id!!, name = ancestor.name)
        }

        return WorkgroupResponse(
            id = workgroup.id!!,
            name = workgroup.name,
            description = workgroup.description,
            parentId = workgroup.parent?.id,
            depth = workgroup.calculateDepth(),
            childCount = workgroup.children.size,
            hasChildren = workgroup.children.isNotEmpty(),
            ancestors = ancestors,
            createdAt = workgroup.createdAt!!,
            updatedAt = workgroup.updatedAt!!,
            version = workgroup.version
        )
    }

    /**
     * Convert a Bean Validation ConstraintViolationException to a single human-readable
     * message. JPA-level @Pattern / @Size violations on the entity bypass the controller
     * @Valid binding, so we surface them here instead of letting Micronaut emit its
     * default `_embedded.errors[]` shape (which the frontend can't read).
     */
    private fun formatViolations(e: ConstraintViolationException): String {
        val violations = e.constraintViolations
        if (violations.isNullOrEmpty()) {
            return e.message ?: "Validation failed"
        }
        return violations.joinToString("; ") { v ->
            val field = v.propertyPath?.toString()?.substringAfterLast('.')
            if (field.isNullOrBlank()) v.message else "$field: ${v.message}"
        }
    }
}

// Request/Response DTOs

/**
 * Request to create a workgroup
 * FR-006: Validates name format and length
 * Feature 039: Criticality field added (defaults to MEDIUM if not provided)
 */
@Serdeable
data class CreateWorkgroupRequest(
    @field:NotBlank(message = "Workgroup name is required")
    @field:Size(min = 1, max = 100, message = "Workgroup name must be 1-100 characters")
    val name: String,

    @field:Size(max = 512, message = "Description must not exceed 512 characters")
    val description: String? = null,

    val criticality: Criticality? = null
)

/**
 * Request to update a workgroup
 * All fields optional, at least one should be provided
 * Feature 039: Criticality field added
 */
@Serdeable
data class UpdateWorkgroupRequest(
    @field:Size(min = 1, max = 100, message = "Workgroup name must be 1-100 characters")
    val name: String? = null,

    @field:Size(max = 512, message = "Description must not exceed 512 characters")
    val description: String? = null,

    val criticality: Criticality? = null
)

/**
 * Request to assign users to workgroup
 */
@Serdeable
data class AssignUsersRequest(
    // Legacy id-only shape — kept for back-compat with CLI / scripts.
    val userIds: List<Long>? = null,
    // New shape — supports both real users (by id) and pending users (by email).
    val userRefs: List<UserResolutionService.UserRef>? = null
) {
    fun isEmpty(): Boolean = userIds.isNullOrEmpty() && userRefs.isNullOrEmpty()
}

/**
 * Request to assign assets to workgroup
 */
@Serdeable
data class AssignAssetsRequest(
    @field:NotNull(message = "Asset IDs are required")
    val assetIds: List<Long>
)

/**
 * Request to bulk-remove users from a workgroup. Distinct shape from AssignUsersRequest
 * because removal only deals in resolved User ids — there's no "pending email" path.
 */
@Serdeable
data class RemoveUsersRequest(
    @field:NotNull(message = "User IDs are required")
    val userIds: List<Long>
)

/**
 * Member listing payload for the Assign Users dialog and other workgroup-membership UIs.
 */
@Serdeable
data class AssignedUserDto(
    val id: Long,
    val username: String,
    val email: String
)

/**
 * Asset listing payload for the Manage Assets dialog. Distinct from CliAssetDto so
 * the non-CLI surface can evolve independently of CLI consumers.
 */
@Serdeable
data class AssignedAssetDto(
    val id: Long,
    val name: String,
    val type: String?,
    val ip: String?,
    val owner: String?
)

/**
 * Response for workgroup list view
 * FR-005: Returns summary with counts
 * Feature 039: Criticality field added
 */
@Serdeable
data class WorkgroupListResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val criticality: Criticality,
    val userCount: Int,
    val assetCount: Int,
    val awsAccountsCount: Long,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
    val parentId: Long?,
    val parentName: String?,
    val depth: Int,
    val ancestors: List<BreadcrumbItem>
)

/**
 * Response for workgroup detail view
 * FR-003: Returns detailed information with counts
 * Feature 039: Criticality field added
 */
@Serdeable
data class WorkgroupDetailResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val criticality: Criticality,
    val userCount: Int,
    val assetCount: Int,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant
)
