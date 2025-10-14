package com.secman.controller

import com.secman.dto.AccountVulnsSummaryDto
import com.secman.service.AccountVulnsService
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
 * Controller for Account Vulns feature - AWS Account-Based Vulnerability Overview
 *
 * Provides endpoint for non-admin users to view vulnerabilities grouped by their AWS accounts.
 *
 * Feature: 018-under-vuln-management
 * Access Control:
 * - Authentication required (JWT)
 * - Admin users are rejected (403 Forbidden - should use System Vulns)
 * - Non-admin users see assets from their mapped AWS accounts only
 * - AWS account mapping is PRIMARY access control (workgroup restrictions do not apply)
 *
 * Related to: User Story 1 (P1) - View Vulnerabilities for Single AWS Account
 */
@Controller("/api/account-vulns")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class AccountVulnsController(
    private val accountVulnsService: AccountVulnsService
) {

    private val logger = LoggerFactory.getLogger(AccountVulnsController::class.java)

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
     * Get vulnerability overview grouped by AWS accounts
     *
     * GET /api/account-vulns
     * Auth: Authenticated non-admin user
     * Response: AccountVulnsSummaryDto with account groups, assets, and vulnerability counts
     *
     * Status Codes:
     * - 200 OK: Successfully retrieved account vulnerability overview
     * - 401 Unauthorized: Missing or invalid authentication token
     * - 403 Forbidden: Admin users (should use System Vulns instead)
     * - 404 Not Found: User has no AWS account mappings
     * - 500 Internal Server Error: Unexpected error
     *
     * Related to:
     * - Feature: 018-under-vuln-management
     * - Contract: specs/018-under-vuln-management/contracts/account-vulns-api.yaml
     * - FR-001: Display assets grouped by AWS account
     * - FR-003: Sort assets by vulnerability count (descending)
     * - FR-005: Admin users redirected to System Vulns
     */
    @Get
    @Transactional(readOnly = true)
    open fun getAccountVulns(authentication: Authentication): HttpResponse<*> {
        return try {
            logger.debug("GET /api/account-vulns - User: {}", authentication.name)

            val summary = accountVulnsService.getAccountVulnsSummary(authentication)

            logger.info(
                "Account vulns retrieved for user {}: {} accounts, {} assets, {} vulnerabilities",
                authentication.name,
                summary.accountGroups.size,
                summary.totalAssets,
                summary.totalVulnerabilities
            )

            HttpResponse.ok(summary)

        } catch (e: IllegalStateException) {
            // Admin user attempted to access Account Vulns view
            logger.warn("Admin access rejected: {}", authentication.name)

            val error = AdminRedirectError(
                message = "Please use System Vulns view",
                redirectUrl = "/system-vulns",
                status = HttpStatus.FORBIDDEN.code
            )

            HttpResponse.status<AdminRedirectError>(HttpStatus.FORBIDDEN).body(error)

        } catch (e: NoSuchElementException) {
            // User has no AWS account mappings
            logger.warn("User {} has no AWS account mappings", authentication.name)

            val error = ErrorResponse(
                message = e.message ?: "No AWS accounts are mapped to your user account. Please contact your administrator.",
                status = HttpStatus.NOT_FOUND.code
            )

            HttpResponse.status<ErrorResponse>(HttpStatus.NOT_FOUND).body(error)

        } catch (e: Exception) {
            // Unexpected error
            logger.error("Error retrieving account vulns for user: {}", authentication.name, e)

            val error = ErrorResponse(
                message = "Internal server error",
                status = HttpStatus.INTERNAL_SERVER_ERROR.code
            )

            HttpResponse.serverError<ErrorResponse>().body(error)
        }
    }
}
