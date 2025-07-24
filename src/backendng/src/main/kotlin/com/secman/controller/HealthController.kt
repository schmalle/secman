package com.secman.controller

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable

@Controller
class HealthController {

    @Serdeable
    data class HealthResponse(
        val status: String,
        val service: String,
        val version: String
    )

    @Get("/health")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun health(): HttpResponse<HealthResponse> {
        val response = HealthResponse(
            status = "UP",
            service = "secman-backend-ng",
            version = "0.1"
        )
        return HttpResponse.ok(response)
    }
}