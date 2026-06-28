package com.secman.controller

import com.secman.domain.DependabotAlert
import com.secman.service.DependabotAlertService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import org.slf4j.LoggerFactory

/**
 * REST controller for GitHub Dependabot alerts.
 *
 * - `POST /api/dependabot-alerts/import` — ingestion endpoint the CLI
 *   (`query dependabot-alerts`) pushes GitHub alerts to. ADMIN/VULN only.
 * - `GET /api/dependabot-alerts` — read model for the Vulnerability
 *   Management UI. ADMIN/VULN/SECCHAMPION.
 */
@Controller("/api/dependabot-alerts")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class DependabotAlertController(
    private val service: DependabotAlertService
) {
    private val log = LoggerFactory.getLogger(DependabotAlertController::class.java)

    /**
     * Ingest a batch of Dependabot alerts (upsert by repository + alert number).
     * Secured to ADMIN/VULN — the same roles that may import CrowdStrike data.
     */
    @Post("/import")
    @Secured("ADMIN", "VULN")
    open fun importAlerts(
        @Body alerts: List<DependabotAlertService.ImportRequest>,
        authentication: Authentication
    ): HttpResponse<*> {
        log.info("Dependabot import request: count={}, user={}", alerts.size, authentication.name)
        return try {
            HttpResponse.ok(service.importAlerts(alerts))
        } catch (e: Exception) {
            log.error("Dependabot import failed: user={}", authentication.name, e)
            HttpResponse.serverError(mapOf("error" to (e.message ?: "Import failed")))
        }
    }

    /**
     * List all ingested Dependabot alerts for the UI.
     */
    @Get
    @Secured("ADMIN", "VULN", "SECCHAMPION")
    open fun list(): HttpResponse<List<DependabotAlert>> =
        HttpResponse.ok(service.listAll())
}
