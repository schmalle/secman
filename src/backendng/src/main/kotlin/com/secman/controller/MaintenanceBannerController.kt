package com.secman.controller

import com.secman.domain.User
import com.secman.dto.MaintenanceBannerRequest
import com.secman.dto.MaintenanceBannerResponse
import com.secman.repository.UserRepository
import com.secman.service.MaintenanceBannerService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import jakarta.validation.Valid

/**
 * REST controller for maintenance banner operations.
 *
 * Endpoints:
 * - POST /api/maintenance-banners - Create banner (ADMIN only)
 * - GET /api/maintenance-banners/active - Get active banners (PUBLIC)
 * - GET /api/maintenance-banners - Get all banners (ADMIN only)
 * - GET /api/maintenance-banners/{id} - Get banner by ID (ADMIN only)
 * - PUT /api/maintenance-banners/{id} - Update banner (ADMIN only)
 * - DELETE /api/maintenance-banners/{id} - Delete banner (ADMIN only)
 *
 * Security:
 * - Admin endpoints require ADMIN role via @Secured annotation
 * - Public endpoint (active banners) has no auth requirement
 */
@Controller("/api/maintenance-banners")
open class MaintenanceBannerController(
    private val service: MaintenanceBannerService,
    private val userRepository: UserRepository
) {

    /**
     * Create a new maintenance banner (ADMIN only).
     *
     * Request validation:
     * - message: Required, 1-2000 characters
     * - startTime: Required
     * - endTime: Required, must be after startTime
     *
     * @param request Banner creation request
     * @param authentication Current user authentication
     * @return HTTP 201 Created with created banner
     */
    @Post
    @Secured("ADMIN")
    open fun createBanner(
        @Valid @Body request: MaintenanceBannerRequest,
        authentication: Authentication
    ): HttpResponse<MaintenanceBannerResponse> {
        // Get authenticated user
        val username = authentication.name
        val user = userRepository.findByUsername(username).orElseThrow {
            IllegalStateException("Authenticated user not found: $username")
        }

        // Create banner
        val banner = service.createBanner(request, user)

        // Return 201 Created
        return HttpResponse.created(MaintenanceBannerResponse.from(banner))
    }

    /**
     * Get all currently active maintenance banners (PUBLIC endpoint).
     *
     * No authentication required - visible to all users.
     * Returns banners where current time is between startTime and endTime.
     * Ordered by creation time (newest first).
     *
     * @return HTTP 200 OK with list of active banners (may be empty)
     */
    @Get("/active")
    @Secured(SecurityRule.IS_ANONYMOUS)  // Public endpoint
    open fun getActiveBanners(): HttpResponse<List<MaintenanceBannerResponse>> {
        val banners = service.getActiveBanners()
        val responses = banners.map { MaintenanceBannerResponse.from(it) }
        return HttpResponse.ok(responses)
    }

    /**
     * Get all maintenance banners (ADMIN only).
     *
     * Returns all banners (past, current, future) ordered by creation time.
     * Used for admin list view.
     *
     * @return HTTP 200 OK with list of all banners
     */
    @Get
    @Secured("ADMIN")
    open fun getAllBanners(): HttpResponse<List<MaintenanceBannerResponse>> {
        val banners = service.getAllBanners()
        val responses = banners.map { MaintenanceBannerResponse.from(it) }
        return HttpResponse.ok(responses)
    }

    /**
     * Get a specific maintenance banner by ID (ADMIN only).
     *
     * @param id Banner ID
     * @return HTTP 200 OK with banner details, or 404 if not found
     */
    @Get("/{id}")
    @Secured("ADMIN")
    open fun getBannerById(@PathVariable id: Long): HttpResponse<MaintenanceBannerResponse> {
        return try {
            val banner = service.getBannerById(id)
            HttpResponse.ok(MaintenanceBannerResponse.from(banner))
        } catch (e: IllegalArgumentException) {
            HttpResponse.notFound()
        }
    }

    /**
     * Update an existing maintenance banner (ADMIN only).
     *
     * @param id Banner ID
     * @param request Update request
     * @return HTTP 200 OK with updated banner, or 404 if not found
     */
    @Put("/{id}")
    @Secured("ADMIN")
    open fun updateBanner(
        @PathVariable id: Long,
        @Valid @Body request: MaintenanceBannerRequest
    ): HttpResponse<MaintenanceBannerResponse> {
        return try {
            val banner = service.updateBanner(id, request)
            HttpResponse.ok(MaintenanceBannerResponse.from(banner))
        } catch (e: IllegalArgumentException) {
            HttpResponse.notFound()
        }
    }

    /**
     * Delete a maintenance banner (ADMIN only).
     *
     * @param id Banner ID
     * @return HTTP 204 No Content on success, or 404 if not found
     */
    @Delete("/{id}")
    @Secured("ADMIN")
    open fun deleteBanner(@PathVariable id: Long): HttpResponse<Void> {
        return try {
            service.deleteBanner(id)
            HttpResponse.noContent()
        } catch (e: IllegalArgumentException) {
            HttpResponse.notFound()
        }
    }
}
