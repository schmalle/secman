package com.secman.controller

import com.secman.dto.GitHubDependabotBatchDto
import com.secman.service.GitHubDependabotImportService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import jakarta.validation.Valid
import org.slf4j.LoggerFactory

/**
 * REST controller for the GitHub Dependabot alert import.
 *
 * The CLI (`dependabot-alerts` command) pulls open HIGH/CRITICAL Dependabot
 * alerts via the GitHub API, groups them by repository, and POSTs the batches
 * here. Each batch becomes a REPOSITORY-type asset with its vulnerabilities.
 *
 * Security: ADMIN or VULN (same as the CrowdStrike import / cli-add paths).
 */
@Controller("/api/github")
@Secured("ADMIN", "VULN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class GitHubDependabotController(
    private val importService: GitHubDependabotImportService
) {
    private val log = LoggerFactory.getLogger(GitHubDependabotController::class.java)

    @Post("/dependabot/import")
    open fun importDependabotAlerts(
        @Body @Valid batches: List<GitHubDependabotBatchDto>,
        authentication: Authentication
    ): HttpResponse<*> {
        val username = authentication.name
        log.info("Received Dependabot import request: repositories={}, user={}", batches.size, username)

        return try {
            val result = importService.importDependabotAlerts(batches, username)
            log.info(
                "Dependabot import completed: repos={}, created={}, updated={}, imported={}, skipped={}, errors={}, user={}",
                result.reposProcessed, result.reposCreated, result.reposUpdated,
                result.vulnerabilitiesImported, result.vulnerabilitiesSkipped, result.errors.size, username
            )
            HttpResponse.ok(result)
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid Dependabot import request: user={}", username, e)
            HttpResponse.badRequest(mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            log.error("Error importing Dependabot alerts: user={}", username, e)
            HttpResponse.status<Map<String, String>>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "An internal error occurred while importing Dependabot alerts"))
        }
    }
}
