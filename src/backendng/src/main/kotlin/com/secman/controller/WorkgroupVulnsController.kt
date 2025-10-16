package com.secman.controller

import com.secman.dto.WorkgroupVulnsSummaryDto
import com.secman.service.WorkgroupVulnsService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.annotation.Transactional
import org.slf4j.LoggerFactory

/**
 * Controller for WG Vulns feature - Workgroup-Based Vulnerability View
 *
 * Provides endpoint for non-admin users to view vulnerabilities grouped by their workgroups.
 *
 * Feature: 022-wg-vulns-handling
 * Access Control:
 * - Authentication required (JWT)
 * - Admin users are rejected (403 Forbidden - should use System Vulns)
 * - Non-admin users see assets from their workgroups only
 * - Workgroup membership is PRIMARY access control
 */
@Controller("/api/wg-vulns")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class WorkgroupVulnsController(
    private val workgroupVulnsService: WorkgroupVulnsService
) {

    private val logger = LoggerFactory.getLogger(WorkgroupVulnsController::class.java)

    @Serdeable
    data class ErrorResponse(
        val message: String,
        val status: Int
    )

    @Serdeable
    data class AdminRedirectError(
        val message: String,
        val redirectUrl: String,
        val status: Int
    )

    /**
     * Get vulnerability overview grouped by workgroups
     *
     * GET /api/wg-vulns
     * Auth: Authenticated non-admin user
     * Response: WorkgroupVulnsSummaryDto with workgroup groups, assets, and vulnerability counts
     *
     * Status Codes:
     * - 200 OK: Successfully retrieved workgroup vulnerability overview
     * - 401 Unauthorized: Missing or invalid authentication token
     * - 403 Forbidden: Admin users (should use System Vulns instead)
     * - 404 Not Found: User has no workgroup memberships
     * - 500 Internal Server Error: Unexpected error
     *
     * Related to:
     * - Feature: 022-wg-vulns-handling
     * - Contract: specs/022-wg-vulns-feature/contracts/wg-vulns-api.yaml
     */
    @Get
    @Transactional(readOnly = true)
    open fun getWorkgroupVulns(authentication: Authentication): HttpResponse<*> {
        return try {
            logger.debug("GET /api/wg-vulns - User: {}", authentication.name)

            val summary = workgroupVulnsService.getWorkgroupVulnsSummary(authentication)

            logger.info(
                "Workgroup vulns retrieved for user {}: {} workgroups, {} assets, {} vulnerabilities",
                authentication.name,
                summary.workgroupGroups.size,
                summary.totalAssets,
                summary.totalVulnerabilities
            )

            HttpResponse.ok(summary)

        } catch (e: IllegalStateException) {
            // Admin user attempted to access WG Vulns view
            logger.warn("Admin access rejected: {}", authentication.name)

            val error = AdminRedirectError(
                message = "Please use System Vulns view",
                redirectUrl = "/vulnerabilities/system",
                status = HttpStatus.FORBIDDEN.code
            )

            HttpResponse.status<AdminRedirectError>(HttpStatus.FORBIDDEN).body(error)

        } catch (e: NoSuchElementException) {
            // User has no workgroup memberships
            logger.warn("User {} has no workgroup memberships", authentication.name)

            val error = ErrorResponse(
                message = e.message ?: "You are not a member of any workgroups. Please contact your administrator.",
                status = HttpStatus.NOT_FOUND.code
            )

            HttpResponse.status<ErrorResponse>(HttpStatus.NOT_FOUND).body(error)

        } catch (e: Exception) {
            // Unexpected error
            logger.error("Error retrieving workgroup vulns for user: {}", authentication.name, e)

            val error = ErrorResponse(
                message = "Internal server error",
                status = HttpStatus.INTERNAL_SERVER_ERROR.code
            )

            HttpResponse.serverError<ErrorResponse>().body(error)
        }
    }
}
