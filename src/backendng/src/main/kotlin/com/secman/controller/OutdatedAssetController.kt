package com.secman.controller

import com.secman.domain.Vulnerability
import com.secman.dto.OutdatedAssetDto
import com.secman.service.OutdatedAssetService
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule

/**
 * REST Controller for Outdated Assets view
 *
 * Provides paginated list of assets with overdue vulnerabilities
 * Access: ADMIN and VULN roles only
 * Returns workgroup-filtered results for VULN users
 *
 * Feature: 034-outdated-assets
 * Task: T020-T022
 * User Story: US1 - View Outdated Assets (P1)
 * Spec reference: contracts/01-get-outdated-assets.md
 */
@Controller("/api/outdated-assets")
@Secured(SecurityRule.IS_AUTHENTICATED)
class OutdatedAssetController(
    private val outdatedAssetService: OutdatedAssetService
) {

    /**
     * GET /api/outdated-assets
     *
     * Returns paginated list of outdated assets with optional filtering
     *
     * Access Control:
     * - ADMIN role: sees all outdated assets
     * - VULN role: sees only assets from assigned workgroups
     * - Other roles: 403 Forbidden
     *
     * Query Parameters:
     * - page (optional): Page number (default: 0)
     * - size (optional): Page size (default: 20)
     * - sort (optional): Sort field and direction (e.g., "oldestVulnDays,desc")
     * - searchTerm (optional): Search by asset name (case-insensitive)
     * - minSeverity (optional): Minimum severity filter (CRITICAL, HIGH, MEDIUM, LOW)
     *
     * Response:
     * - 200 OK: Paginated list of outdated assets
     * - 401 Unauthorized: Not authenticated
     * - 403 Forbidden: User lacks ADMIN or VULN role
     *
     * Task: T020, T021, T022
     * Spec reference: FR-001, FR-008, FR-009, FR-011, FR-012, FR-013
     */
    @Get
    @Secured("ADMIN", "VULN")
    fun getOutdatedAssets(
        authentication: Authentication,
        @QueryValue(defaultValue = "") searchTerm: String?,
        @QueryValue(defaultValue = "") minSeverity: String?,
        pageable: Pageable
    ): HttpResponse<Page<OutdatedAssetDto>> {
        // Validate user has required role
        val hasRequiredRole = authentication.roles.any { it == "ADMIN" || it == "VULN" }
        if (!hasRequiredRole) {
            return HttpResponse.status(HttpStatus.FORBIDDEN)
        }

        // Get outdated assets with workgroup filtering applied by service
        val page = outdatedAssetService.getOutdatedAssets(
            authentication = authentication,
            searchTerm = searchTerm.takeIf { !it.isNullOrBlank() },
            minSeverity = minSeverity.takeIf { !it.isNullOrBlank() },
            pageable = pageable
        )

        // Map to DTOs
        val dtoPage = page.map { OutdatedAssetDto.from(it) }

        return HttpResponse.ok(dtoPage)
    }

    /**
     * GET /api/outdated-assets/last-refresh
     *
     * Returns the timestamp of the last materialized view refresh
     *
     * Response:
     * - 200 OK: { "lastRefreshTimestamp": "2024-10-26T14:30:00" }
     * - 204 No Content: No refresh has occurred yet
     *
     * Task: T022
     * Spec reference: FR-017
     */
    @Get("/last-refresh")
    @Secured("ADMIN", "VULN")
    fun getLastRefreshTimestamp(authentication: Authentication): HttpResponse<Map<String, Any>> {
        // Validate user has required role
        val hasRequiredRole = authentication.roles.any { it == "ADMIN" || it == "VULN" }
        if (!hasRequiredRole) {
            return HttpResponse.status(HttpStatus.FORBIDDEN)
        }

        val timestamp = outdatedAssetService.getLastRefreshTimestamp()

        return if (timestamp != null) {
            HttpResponse.ok(mapOf("lastRefreshTimestamp" to timestamp))
        } else {
            HttpResponse.noContent()
        }
    }

    /**
     * GET /api/outdated-assets/count
     *
     * Returns count of outdated assets visible to the user (workgroup-filtered)
     *
     * Response:
     * - 200 OK: { "count": 42 }
     *
     * Task: T022
     * Spec reference: FR-016
     */
    @Get("/count")
    @Secured("ADMIN", "VULN")
    fun getOutdatedAssetsCount(authentication: Authentication): HttpResponse<Map<String, Any>> {
        // Validate user has required role
        val hasRequiredRole = authentication.roles.any { it == "ADMIN" || it == "VULN" }
        if (!hasRequiredRole) {
            return HttpResponse.status(HttpStatus.FORBIDDEN)
        }

        val count = outdatedAssetService.countOutdatedAssets(authentication)

        return HttpResponse.ok(mapOf("count" to count))
    }

    /**
     * GET /api/outdated-assets/{id}
     *
     * Get single outdated asset by ID with workgroup-based access control
     *
     * Access Control:
     * - ADMIN role: can access any outdated asset
     * - VULN role: can only access assets from assigned workgroups
     *
     * Response:
     * - 200 OK: OutdatedAssetDto
     * - 404 Not Found: Asset not found or user lacks access
     * - 403 Forbidden: User lacks ADMIN or VULN role
     *
     * Task: T035-T036
     * User Story: US2 - View Asset Details
     * Spec reference: contracts/02-get-outdated-asset-by-id.md
     */
    @Get("/{id}")
    @Secured("ADMIN", "VULN")
    fun getOutdatedAssetById(
        id: Long,
        authentication: Authentication
    ): HttpResponse<OutdatedAssetDto> {
        // Validate user has required role
        val hasRequiredRole = authentication.roles.any { it == "ADMIN" || it == "VULN" }
        if (!hasRequiredRole) {
            return HttpResponse.status(HttpStatus.FORBIDDEN)
        }

        // Get asset with access control
        val asset = outdatedAssetService.getOutdatedAssetById(id, authentication)
            ?: return HttpResponse.notFound()

        // Map to DTO
        val dto = OutdatedAssetDto.from(asset)

        return HttpResponse.ok(dto)
    }

    /**
     * GET /api/outdated-assets/{id}/vulnerabilities
     *
     * Get paginated list of vulnerabilities for an outdated asset
     *
     * Access Control:
     * - Requires user to have access to the outdated asset (checked via getOutdatedAssetById)
     *
     * Query Parameters:
     * - page, size, sort (standard pagination)
     *
     * Response:
     * - 200 OK: Page of vulnerabilities
     * - 404 Not Found: Asset not found or user lacks access
     * - 403 Forbidden: User lacks ADMIN or VULN role
     *
     * Task: T037-T038
     * User Story: US2 - View Asset Details
     * Spec reference: contracts/03-get-asset-vulnerabilities.md
     */
    @Get("/{id}/vulnerabilities")
    @Secured("ADMIN", "VULN")
    fun getAssetVulnerabilities(
        id: Long,
        authentication: Authentication,
        pageable: Pageable
    ): HttpResponse<Page<Vulnerability>> {
        // Validate user has required role
        val hasRequiredRole = authentication.roles.any { it == "ADMIN" || it == "VULN" }
        if (!hasRequiredRole) {
            return HttpResponse.status(HttpStatus.FORBIDDEN)
        }

        // First check if user has access to the outdated asset
        val outdatedAsset = outdatedAssetService.getOutdatedAssetById(id, authentication)
            ?: return HttpResponse.notFound()

        // Get vulnerabilities for the asset
        val vulnerabilities = outdatedAssetService.getVulnerabilitiesForAsset(
            outdatedAsset.assetId,
            pageable
        )

        return HttpResponse.ok(vulnerabilities)
    }
}
