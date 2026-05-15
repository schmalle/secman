package com.secman.controller

import com.secman.service.ComplianceAssistantService
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.serde.annotation.Serdeable

/**
 * Feature 088 — exposes whether AI risk-assessment is currently enabled.
 *
 * Returns a tiny `{enabled, model}` payload so the SECCHAMPION/ADMIN UI can
 * hide the "AI Pre-fill" button when the feature is off without trying to
 * fetch the full ADMIN-only `/api/settings/app` payload.
 */
@Controller("/api/ai-risk-assessment")
@Secured("ADMIN", "SECCHAMPION")
@ExecuteOn(TaskExecutors.BLOCKING)
open class AiFeatureStatusController(
    private val complianceAssistantService: ComplianceAssistantService
) {
    @Serdeable
    data class Status(val enabled: Boolean, val model: String)

    @Get("/status")
    open fun status(): Status = Status(
        enabled = complianceAssistantService.isEnabled(),
        model = complianceAssistantService.currentModel
    )
}
