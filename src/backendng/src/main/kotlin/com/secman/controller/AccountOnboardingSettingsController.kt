package com.secman.controller

import com.secman.dto.AccountOnboardingSettingsDto
import com.secman.service.AccountOnboardingSettingsService
import com.secman.service.AuditLogService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication

@Controller("/api/account-onboarding/settings")
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("ADMIN", "SECCHAMPION")
class AccountOnboardingSettingsController(
    private val settings: AccountOnboardingSettingsService,
    private val audit: AuditLogService
) {
    @Get
    @Secured("ADMIN", "SECCHAMPION")
    fun get() = settings.get()

    @Put
    @Secured("ADMIN")
    fun save(@Body request: AccountOnboardingSettingsDto, authentication: Authentication): HttpResponse<*> =
        try {
            val saved = settings.save(request)
            audit.logAction(authentication, "UPDATE", "AccountOnboardingSettings", details = "mode=${saved.mode}; outcome=SUCCESS")
            HttpResponse.ok(saved)
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(mapOf("error" to "VALIDATION_ERROR", "message" to e.message))
        }
}
