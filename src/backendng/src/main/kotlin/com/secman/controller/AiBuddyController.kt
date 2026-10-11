package com.secman.controller

import com.secman.dto.AiBuddyError
import com.secman.service.aibuddy.AiBuddyFailure
import com.secman.service.aibuddy.AiBuddyJobService
import com.secman.service.aibuddy.AiBuddyJson
import com.secman.service.aibuddy.AiBuddyInventoryInput
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule

@Controller("/api/ai-buddy")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
@Produces(MediaType.APPLICATION_JSON)
open class AiBuddyController(private val jobs: AiBuddyJobService, private val json: AiBuddyJson) {
    @Get("/capabilities")
    open fun capabilities(authentication: Authentication): HttpResponse<*> = respond { jobs.capabilities(authentication) }

    @Post("/assets/{assetId}/jobs")
    @Consumes(MediaType.APPLICATION_JSON)
    open fun start(assetId: Long, @Body body: String, authentication: Authentication): HttpResponse<*> = respond(HttpStatus.ACCEPTED) {
        if (body.toByteArray().size > 1024) throw AiBuddyFailure("INVALID_REQUEST")
        val request = json.parse(body.toByteArray())
        if (!request.isObject || request.fieldNames().asSequence().any { it !in setOf("requestId", "inventory") } || !request.path("requestId").isTextual) throw AiBuddyFailure("INVALID_REQUEST")
        val inventory = if (request.has("inventory")) AiBuddyInventoryInput.parse(request.path("inventory")) else null
        jobs.start(assetId, request.path("requestId").asText(), authentication, inventory)
    }

    @Get("/assets/{assetId}/jobs/{jobId}")
    open fun status(assetId: Long, jobId: String, authentication: Authentication): HttpResponse<*> = respond { jobs.status(assetId, jobId, authentication) }

    @Get("/assets/{assetId}/jobs/{jobId}/report")
    open fun report(assetId: Long, jobId: String, authentication: Authentication): HttpResponse<*> = respond { jobs.report(assetId, jobId, authentication) }

    @Delete("/assets/{assetId}/jobs/{jobId}")
    open fun cancel(assetId: Long, jobId: String, authentication: Authentication): HttpResponse<*> = respond {
        jobs.cancel(assetId, jobId, authentication)
        mapOf("state" to "CANCELLED")
    }

    private fun respond(status: HttpStatus = HttpStatus.OK, action: () -> Any): HttpResponse<*> = try {
        HttpResponse.status<Any>(status).body(action()).header("Cache-Control", "no-store, private").header("Pragma", "no-cache")
    } catch (e: HttpStatusException) {
        HttpResponse.status<Any>(e.status).body(AiBuddyError(e.message ?: "AUTH_REQUIRED")).header("Cache-Control", "no-store, private")
    } catch (e: AiBuddyFailure) {
        val code = when (e.code) {
            "INVALID_REQUEST", "REQUEST_ID_CONFLICT", "OUTPUT_INVALID" -> HttpStatus.BAD_REQUEST
            "REPORT_NOT_READY" -> HttpStatus.CONFLICT
            "REQUEST_LIMITED", "CAPACITY_EXHAUSTED", "BUDGET_EXHAUSTED" -> HttpStatus.TOO_MANY_REQUESTS
            "SCOPE_TOO_LARGE" -> HttpStatus.UNPROCESSABLE_ENTITY
            else -> HttpStatus.SERVICE_UNAVAILABLE
        }
        val response = HttpResponse.status<Any>(code).body(AiBuddyError(e.code, e.retryable, e.retryAfter)).header("Cache-Control", "no-store, private")
        if (e.retryAfter != null) response.header("Retry-After", e.retryAfter.toString())
        response
    }
}
