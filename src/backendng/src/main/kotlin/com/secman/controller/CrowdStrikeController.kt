package com.secman.controller

import com.secman.dto.CrowdStrikeQueryResponse
import com.secman.dto.CrowdStrikeSaveRequest
import com.secman.dto.CrowdStrikeSaveResponse
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.dto.ImportStatisticsDto
import com.secman.service.CrowdStrikeError
import com.secman.service.CrowdStrikeQueryService
import com.secman.service.CrowdStrikeVulnerabilityImportService
import com.secman.service.CrowdStrikeVulnerabilityService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory

/**
 * REST controller for CrowdStrike Falcon API integration
 *
 * Endpoints:
 * - GET /api/vulnerabilities - Query vulnerabilities by hostname (with filtering & pagination)
 * - GET /api/crowdstrike/vulnerabilities - Query vulnerabilities by hostname (legacy)
 * - POST /api/crowdstrike/vulnerabilities/save - Save vulnerabilities to database (single or batch)
 *
 * Related to:
 * - Feature 023-create-in-the (Phase 5: Backend API Integration)
 * - Feature 032-servers-query-import (Batch Import)
 * Tasks: T062-T070, T017-T023
 */
@Controller("/api")
@Secured("ADMIN", "VULN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class CrowdStrikeController(
    private val crowdStrikeService: CrowdStrikeVulnerabilityService,
    private val queryService: CrowdStrikeQueryService,
    private val importService: CrowdStrikeVulnerabilityImportService
) {
    private val log = LoggerFactory.getLogger(CrowdStrikeController::class.java)

    /**
     * Query CrowdStrike for vulnerabilities with filtering and pagination
     *
     * Task: T062-T065
     *
     * @param hostname System hostname to query
     * @param severity Optional severity filter (critical, high, medium, low)
     * @param product Optional product filter (substring match)
     * @param limit Result limit (default: 100, max: 1000)
     * @return CrowdStrikeQueryResponse with vulnerabilities
     *
     * Error responses:
     * - 400: Invalid parameters
     * - 401: Unauthorized
     * - 403: Forbidden
     * - 404: Hostname not found
     * - 429: Rate limit exceeded
     * - 500: Server error
     */
    @Get("/vulnerabilities")
    open fun queryVulnerabilities(
        @QueryValue @NotBlank hostname: String,
        @QueryValue severity: String? = null,
        @QueryValue product: String? = null,
        @QueryValue limit: Int? = null
    ): HttpResponse<*> {
        log.info(
            "Received vulnerability query: hostname={}, severity={}, product={}, limit={}",
            hostname, severity, product, limit
        )

        return try {
            // Sanitize hostname
            val sanitizedHostname = sanitizeHostname(hostname)

            // Validate limit
            val pageSize = when {
                limit == null -> 100
                limit < 1 -> {
                    throw IllegalArgumentException("Limit must be greater than 0")
                }
                limit > 1000 -> {
                    throw IllegalArgumentException("Limit cannot exceed 1000")
                }
                else -> limit
            }

            // Query with filtering
            val response = queryService.queryVulnerabilities(
                hostname = sanitizedHostname,
                severity = severity,
                product = product,
                limit = pageSize
            )

            log.info(
                "Query successful: hostname={}, severity={}, product={}, found={}",
                sanitizedHostname, severity, product, response.vulnerabilities.size
            )
            HttpResponse.ok(response)
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid query parameters: {}", e.message)
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid parameters")))
        } catch (e: CrowdStrikeError.NotFoundError) {
            log.warn("Hostname not found: {}", hostname)
            HttpResponse.notFound(mapOf("error" to e.message))
        } catch (e: CrowdStrikeError.RateLimitError) {
            log.warn("Rate limit exceeded")
            val retryAfter = e.retryAfterSeconds ?: 60
            HttpResponse.status<Map<String, String>>(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", retryAfter.toString())
                .body(mapOf("error" to e.message))
        } catch (e: CrowdStrikeError.ConfigurationError) {
            log.error("Configuration error: {}", e.message)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to e.message))
        } catch (e: CrowdStrikeError) {
            log.error("CrowdStrike error: {}", e.message)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to (e.message ?: "CrowdStrike API error")))
        } catch (e: Exception) {
            log.error("Unexpected error", e)
            HttpResponse.serverError(mapOf("error" to "An unexpected error occurred"))
        }
    }

    /**
     * Legacy endpoint: Query CrowdStrike for system vulnerabilities
     *
     * @param hostname System hostname to query
     * @return CrowdStrikeQueryResponse with vulnerabilities
     */
    @Get("/crowdstrike/vulnerabilities")
    open fun queryVulnerabilitiesLegacy(
        @QueryValue @NotBlank hostname: String
    ): HttpResponse<*> {
        log.info("Received legacy CrowdStrike query request: hostname={}", hostname)

        return try {
            val sanitizedHostname = sanitizeHostname(hostname)
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
     * Save CrowdStrike vulnerabilities to database with asset auto-creation
     *
     * Supports two modes:
     * 1. Single server (Feature 030): CrowdStrikeSaveRequest
     * 2. Batch import (Feature 032): List<CrowdStrikeVulnerabilityBatchDto>
     *
     * Features: 030-crowdstrike-asset-auto-creation, 032-servers-query-import
     * Tasks: T012, T013, T017, T023
     *
     * @param request Save request (single or batch)
     * @param authentication Current authenticated user
     * @return Save response with statistics
     */
    @Post("/crowdstrike/vulnerabilities/save")
    open fun saveVulnerabilities(
        @Body @Valid request: CrowdStrikeSaveRequest,
        authentication: Authentication  // T012: Pass authentication to service
    ): HttpResponse<*> {
        val username = authentication.name
        log.info("Received CrowdStrike save request: hostname={}, count={}, user={}", request.hostname, request.vulnerabilities.size, username)

        return try {
            val response = crowdStrikeService.saveToDatabase(request, authentication)  // T012: Pass both parameters

            log.info("CrowdStrike save successful: {}, user={}", response.message, username)
            HttpResponse.ok(response)
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid save request: user={}", username, e)
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            // T013: Role-based error messages
            val isAdmin = authentication.roles.contains("ADMIN")
            val errorMessage = if (isAdmin) {
                "Database error: ${e.message ?: "Unable to save vulnerabilities"}"
            } else {
                "Database error: Unable to save vulnerabilities"
            }

            log.error("Database error saving vulnerabilities: user={}", username, e)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to errorMessage))
        }
    }

    /**
     * Batch import server vulnerabilities from CrowdStrike
     *
     * Feature: 032-servers-query-import
     * Tasks: T017, T018, T023
     * Spec reference: FR-008, FR-015
     *
     * @param batches List of server vulnerability batches
     * @param authentication Current authenticated user
     * @return ImportStatisticsDto with detailed statistics
     *
     * Security: Inherits controller-level @Secured("ADMIN", "VULN") - only ADMIN/VULN roles can import
     *
     * Error responses:
     * - 400: Invalid request (validation failure)
     * - 401: Unauthorized
     * - 403: Forbidden (requires ADMIN or VULN role)
     * - 500: Server error
     */
    @Post("/crowdstrike/servers/import")
    open fun importServerVulnerabilities(
        @Body @Valid batches: List<CrowdStrikeVulnerabilityBatchDto>,
        authentication: Authentication
    ): HttpResponse<*> {
        val username = authentication.name
        log.info("Received batch import request: servers={}, user={}", batches.size, username)

        return try {
            // T023: Call import service
            val statistics = importService.importServerVulnerabilities(batches)

            log.info("Batch import completed: servers processed={}, created={}, updated={}, vulnerabilities imported={}, skipped={}, errors={}, user={}",
                statistics.serversProcessed, statistics.serversCreated, statistics.serversUpdated,
                statistics.vulnerabilitiesImported, statistics.vulnerabilitiesSkipped, statistics.errors.size, username)

            // T023: Return statistics
            HttpResponse.ok(statistics)
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid batch import request: user={}", username, e)
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            val isAdmin = authentication.roles.contains("ADMIN")
            val errorMessage = if (isAdmin) {
                "Import error: ${e.message ?: "Unable to import server vulnerabilities"}"
            } else {
                "Import error: Unable to import server vulnerabilities"
            }

            log.error("Error importing server vulnerabilities: user={}", username, e)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to errorMessage))
        }
    }

    /**
     * Sanitize and validate hostname
     *
     * @param hostname Raw hostname input
     * @return Sanitized hostname
     * @throws IllegalArgumentException if hostname format is invalid
     */
    private fun sanitizeHostname(hostname: String): String {
        val trimmed = hostname.trim()

        val hostnameRegex = Regex("^[a-zA-Z0-9.-]+$")
        if (!hostnameRegex.matches(trimmed)) {
            throw IllegalArgumentException("Invalid hostname format. Only alphanumeric characters, dots, and hyphens are allowed.")
        }

        if (trimmed.contains("--") || trimmed.contains("..") || trimmed.startsWith("-") || trimmed.endsWith("-")) {
            throw IllegalArgumentException("Invalid hostname format.")
        }

        return trimmed
    }
}
