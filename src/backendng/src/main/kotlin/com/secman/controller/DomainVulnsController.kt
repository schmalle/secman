package com.secman.controller

import com.secman.dto.DomainVulnsSummaryDto
import com.secman.service.DomainVulnsService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import org.slf4j.LoggerFactory

/**
 * Domain Vulnerabilities Controller
 *
 * Feature: 042-domain-vulnerabilities-view
 *
 * Provides REST endpoint for domain-based vulnerability view.
 * Queries CrowdStrike Falcon API directly based on user's domain mappings.
 *
 * Endpoint: GET /api/domain-vulns
 * Access: Authenticated users only (non-admin)
 * Response: Domain-grouped vulnerabilities from Falcon API
 *
 * Similar to AccountVulnsController but:
 * - Uses domain mappings instead of AWS account mappings
 * - Queries Falcon API directly (not local database)
 * - Returns real-time data from CrowdStrike
 *
 * @see AccountVulnsController
 */
@Controller("/api/domain-vulns")
@Secured(SecurityRule.IS_AUTHENTICATED)
class DomainVulnsController(
    private val domainVulnsService: DomainVulnsService
) {
    private val log = LoggerFactory.getLogger(DomainVulnsController::class.java)

    /**
     * Get domain-based vulnerabilities from Falcon API
     *
     * Workflow:
     * 1. Verify user is NOT admin (admins should use System Vulns view)
     * 2. Get user's domain mappings from UserMapping table
     * 3. Query CrowdStrike Falcon API for each domain
     * 4. Aggregate and return results grouped by domain
     *
     * Response Codes:
     * - 200 OK: Successfully retrieved domain vulnerabilities
     * - 403 Forbidden: User is admin (should use System Vulns view)
     * - 404 Not Found: User has no domain mappings
     * - 500 Internal Server Error: Falcon API error or service error
     *
     * @param authentication User authentication context (injected by Micronaut Security)
     * @return DomainVulnsSummaryDto with domain-grouped vulnerabilities
     */
    @Get
    fun getDomainVulns(authentication: Authentication): HttpResponse<*> {
        val email = authentication.attributes["email"]?.toString() ?: "unknown"
        log.info("GET /api/domain-vulns - user: {}", email)

        return try {
            // Check if user is admin
            if (authentication.roles.contains("ADMIN")) {
                log.warn("Admin user {} attempted to access domain vulns view", email)
                return HttpResponse.status<Any>(io.micronaut.http.HttpStatus.FORBIDDEN)
                    .body(mapOf(
                        "message" to "Admin users should use System Vulnerabilities view",
                        "redirectTo" to "/system-vulns"
                    ))
            }

            // Get domain vulnerabilities from service
            val summary = domainVulnsService.getDomainVulnsSummary(authentication)

            log.info("Domain vulns retrieved: user={}, domains={}, devices={}, vulnerabilities={}",
                email, summary.domainGroups.size, summary.totalDevices, summary.totalVulnerabilities)

            HttpResponse.ok(summary)
        } catch (e: IllegalArgumentException) {
            // User has no domain mappings
            log.warn("User {} has no domain mappings: {}", email, e.message)
            HttpResponse.status<Any>(io.micronaut.http.HttpStatus.NOT_FOUND)
                .body(mapOf(
                    "message" to (e.message ?: "No domain mappings found for user"),
                    "error" to "NO_DOMAIN_MAPPINGS"
                ))
        } catch (e: IllegalStateException) {
            // Falcon API not configured or other state error
            log.error("Service error for user {}: {}", email, e.message)
            HttpResponse.status<Any>(io.micronaut.http.HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf(
                    "message" to (e.message ?: "Service configuration error"),
                    "error" to "SERVICE_ERROR"
                ))
        } catch (e: Exception) {
            // Unexpected error
            log.error("Unexpected error getting domain vulns for user {}", email, e)
            HttpResponse.serverError<Any>()
                .body(mapOf(
                    "message" to "Failed to retrieve domain vulnerabilities: ${e.message}",
                    "error" to "INTERNAL_ERROR"
                ))
        }
    }
}
