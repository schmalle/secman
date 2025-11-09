package com.secman.controller

import com.secman.dto.DomainVulnsSummaryDto
import com.secman.service.DomainVulnsService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import org.slf4j.LoggerFactory

/**
 * Domain Vulnerabilities Controller
 *
 * Feature: 043-crowdstrike-domain-import
 *
 * Provides REST endpoint for domain-based vulnerability view.
 * Queries secman database for vulnerabilities based on user's domain mappings.
 *
 * Endpoint: GET /api/domain-vulns
 * Access: Authenticated users only (non-admin)
 * Response: Domain-grouped vulnerabilities from secman database
 *
 * Similar to AccountVulnsController but:
 * - Uses domain mappings instead of AWS account mappings
 * - Queries local database (not CrowdStrike Falcon API)
 * - Returns data from secman database
 *
 * @see AccountVulnsController
 */
@Controller("/api/domain-vulns")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
class DomainVulnsController(
    private val domainVulnsService: DomainVulnsService
) {
    private val log = LoggerFactory.getLogger(DomainVulnsController::class.java)

    /**
     * Get domain-based vulnerabilities from secman database
     *
     * Workflow:
     * 1. Verify user is NOT admin (admins should use System Vulns view)
     * 2. Get user's domain mappings from UserMapping table
     * 3. Query secman database for assets matching user's domains
     * 4. Query vulnerabilities for those assets
     * 5. Aggregate and return results grouped by domain
     *
     * Response Codes:
     * - 200 OK: Successfully retrieved domain vulnerabilities
     * - 403 Forbidden: User is admin (should use System Vulns view)
     * - 404 Not Found: User has no domain mappings
     * - 500 Internal Server Error: Database error or service error
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
