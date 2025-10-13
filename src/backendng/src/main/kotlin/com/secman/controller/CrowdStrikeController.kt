package com.secman.controller

import com.secman.dto.CrowdStrikeQueryResponse
import com.secman.dto.CrowdStrikeSaveRequest
import com.secman.dto.CrowdStrikeSaveResponse
import com.secman.service.CrowdStrikeError
import com.secman.service.CrowdStrikeVulnerabilityService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory

/**
 * REST controller for CrowdStrike Falcon API integration
 *
 * Endpoints:
 * - GET /api/crowdstrike/vulnerabilities - Query vulnerabilities by hostname
 * - POST /api/crowdstrike/vulnerabilities/save - Save vulnerabilities to database
 *
 * Related to: Feature 015-we-have-currently (CrowdStrike System Vulnerability Lookup)
 * Tasks: T027-T028 [US1-Impl], T051 [US3-Impl]
 */
@Controller("/api/crowdstrike")
@Secured("ADMIN", "VULN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class CrowdStrikeController(
    private val crowdStrikeService: CrowdStrikeVulnerabilityService
) {
    private val log = LoggerFactory.getLogger(CrowdStrikeController::class.java)

    /**
     * Query CrowdStrike for system vulnerabilities
     *
     * Task: T028 [US1-Impl]
     *
     * @param hostname System hostname to query
     * @return CrowdStrikeQueryResponse with vulnerabilities
     *
     * Error responses:
     * - 400: Invalid hostname format
     * - 401: Unauthorized (missing JWT token)
     * - 403: Forbidden (user lacks ADMIN or VULN role)
     * - 404: System not found in CrowdStrike
     * - 429: Rate limit exceeded
     * - 500: Internal server error (auth failed, API unavailable, config missing, etc.)
     */
    @Get("/vulnerabilities")
    open fun queryVulnerabilities(
        @QueryValue @NotBlank hostname: String
    ): HttpResponse<*> {
        log.info("Received CrowdStrike query request: hostname={}", hostname)

        return try {
            // Sanitize hostname (trim whitespace, validate format)
            val sanitizedHostname = sanitizeHostname(hostname)

            // Query CrowdStrike
            val response = crowdStrikeService.queryByHostname(sanitizedHostname)

            log.info("CrowdStrike query successful: hostname={}, count={}", sanitizedHostname, response.totalCount)
            HttpResponse.ok(response)
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid hostname: {}", hostname, e)
            HttpResponse.badRequest(mapOf("error" to "Invalid hostname format"))
        } catch (e: CrowdStrikeError.ConfigurationError) {
            log.error("CrowdStrike configuration error", e)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "CrowdStrike API credentials not configured. Contact administrator."))
        } catch (e: CrowdStrikeError.AuthenticationError) {
            log.error("CrowdStrike authentication failed", e)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "CrowdStrike authentication failed"))
        } catch (e: CrowdStrikeError.NotFoundError) {
            log.warn("System not found in CrowdStrike: {}", hostname)
            HttpResponse.notFound(mapOf("error" to "System '$hostname' not found in CrowdStrike"))
        } catch (e: CrowdStrikeError.RateLimitError) {
            log.warn("CrowdStrike rate limit exceeded")
            val retryAfter = e.retryAfterSeconds ?: 30
            HttpResponse.status<Map<String, String>>(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", retryAfter.toString())
                .body(mapOf("error" to "CrowdStrike API rate limit exceeded. Try again in $retryAfter seconds."))
        } catch (e: CrowdStrikeError.NetworkError) {
            log.error("Unable to reach CrowdStrike API", e)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "Unable to reach CrowdStrike API. Please try again later."))
        } catch (e: CrowdStrikeError.ServerError) {
            log.error("CrowdStrike service error", e)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "CrowdStrike service temporarily unavailable. Please try again later."))
        } catch (e: Exception) {
            log.error("Unexpected error querying CrowdStrike", e)
            HttpResponse.serverError(mapOf("error" to "An unexpected error occurred. Please try again later."))
        }
    }

    /**
     * Save CrowdStrike vulnerabilities to database
     *
     * Task: T051 [US3-Impl]
     *
     * @param request Save request with hostname and vulnerabilities
     * @return CrowdStrikeSaveResponse with save results
     *
     * Error responses:
     * - 400: Invalid request (missing hostname, empty vulnerabilities list, validation errors)
     * - 401: Unauthorized (missing JWT token)
     * - 403: Forbidden (user lacks ADMIN or VULN role)
     * - 500: Database error
     */
    @Post("/vulnerabilities/save")
    open fun saveVulnerabilities(
        @Body @Valid request: CrowdStrikeSaveRequest
    ): HttpResponse<*> {
        log.info("Received CrowdStrike save request: hostname={}, count={}", request.hostname, request.vulnerabilities.size)

        return try {
            val response = crowdStrikeService.saveToDatabase(request.hostname, request.vulnerabilities)

            log.info("CrowdStrike save successful: {}", response.message)
            HttpResponse.ok(response)
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid save request", e)
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            log.error("Database error saving vulnerabilities", e)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "Database error: Unable to save vulnerabilities"))
        }
    }

    /**
     * Sanitize and validate hostname
     *
     * Task: T063 [Polish]
     *
     * @param hostname Raw hostname input
     * @return Sanitized hostname
     * @throws IllegalArgumentException if hostname format is invalid
     */
    private fun sanitizeHostname(hostname: String): String {
        // Trim whitespace
        val trimmed = hostname.trim()

        // Validate format (alphanumeric, dots, hyphens only)
        val hostnameRegex = Regex("^[a-zA-Z0-9.-]+$")
        if (!hostnameRegex.matches(trimmed)) {
            throw IllegalArgumentException("Invalid hostname format. Only alphanumeric characters, dots, and hyphens are allowed.")
        }

        // Reject hostnames with potential injection attempts
        if (trimmed.contains("--") || trimmed.contains("..") || trimmed.startsWith("-") || trimmed.endsWith("-")) {
            throw IllegalArgumentException("Invalid hostname format.")
        }

        return trimmed
    }
}
