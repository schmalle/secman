package com.secman.controller

import com.secman.domain.CyberdefSeverity
import com.secman.domain.CyberdefTicketStatus
import com.secman.dto.CyberdefCommentRequest
import com.secman.dto.CyberdefTransitionRequest
import com.secman.service.cyberdef.CyberdefTicketActor
import com.secman.service.cyberdef.CyberdefTicketCreateRequest
import com.secman.service.cyberdef.CyberdefTicketFilter
import com.secman.service.cyberdef.CyberdefTicketQueryService
import com.secman.service.cyberdef.CyberdefTicketResult
import com.secman.service.cyberdef.CyberdefTicketService
import com.secman.service.cyberdef.CyberdefTicketWorkflow
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Account tickets for the web UI. Any signed-in user may call the read and answer
 * endpoints; what they get is scoped to the AWS accounts they can access (404 outside
 * that scope, so ids are not an oracle). Submission and delivery management carry
 * their own role gate. MCP mirrors this surface, see docs/CYBERDEF_TICKETS.md.
 */
@Controller("/api/security-tickets")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class CyberdefTicketController(
    private val service: CyberdefTicketService,
    private val queries: CyberdefTicketQueryService
) {

    @Get
    open fun list(
        @QueryValue status: List<String>?,
        @QueryValue severity: List<String>?,
        @QueryValue accountId: String?,
        @QueryValue(defaultValue = "false") createdByMe: Boolean,
        @QueryValue(defaultValue = "false") overdue: Boolean,
        @QueryValue updatedSince: String?,
        @QueryValue q: String?,
        @QueryValue(defaultValue = "0") page: Int,
        @QueryValue(defaultValue = "25") size: Int,
        @QueryValue(defaultValue = "updatedAt") sort: String,
        @QueryValue(defaultValue = "desc") direction: String,
        authentication: Authentication
    ): HttpResponse<*> {
        val statuses = parseEnums(status, CyberdefTicketStatus.entries) ?: return badRequest("Unknown status filter")
        val severities = parseEnums(severity, CyberdefSeverity.entries) ?: return badRequest("Unknown severity filter")
        val since = updatedSince?.takeIf { it.isNotBlank() }?.let {
            try {
                Instant.parse(it)
            } catch (e: DateTimeParseException) {
                return badRequest("updatedSince must be an ISO-8601 instant")
            }
        }
        val filter = CyberdefTicketFilter(
            statuses = statuses, severities = severities, accountId = accountId, createdByMe = createdByMe,
            overdueOnly = overdue, updatedSince = since, search = q, page = page, size = size,
            sort = sort, descending = !direction.equals("asc", ignoreCase = true)
        )
        return HttpResponse.ok(queries.list(CyberdefTicketActor.from(authentication), filter))
    }

    @Get("/summary")
    open fun summary(authentication: Authentication): HttpResponse<*> =
        HttpResponse.ok(queries.counts(CyberdefTicketActor.from(authentication)))

    @Get("/{id}")
    open fun detail(@PathVariable id: Long, authentication: Authentication): HttpResponse<*> =
        respond(queries.detail(CyberdefTicketActor.from(authentication), id))

    @Post("/{id}/transitions")
    open fun transition(
        @PathVariable id: Long,
        @Body request: CyberdefTransitionRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        val actor = CyberdefTicketActor.from(authentication)
        return if (request.dryRun == true) {
            respond(service.previewTransition(actor, id, request.toStatus, request.comment, request.expectedVersion))
        } else {
            respond(service.transition(actor, id, request.toStatus, request.comment, request.expectedVersion))
        }
    }

    @Post("/{id}/comments")
    open fun comment(@PathVariable id: Long, @Body request: CyberdefCommentRequest, authentication: Authentication): HttpResponse<*> =
        respond(service.comment(CyberdefTicketActor.from(authentication), id, request.comment))

    /** Web form submission (Phase 2 UI). 201 created, 200 duplicate or dry run, 4xx with the reason otherwise. */
    @Post
    @Secured("ADMIN", "CYBERDEF")
    open fun create(@Body request: CyberdefTicketCreateRequest, authentication: Authentication): HttpResponse<*> {
        val result = service.create(CyberdefTicketActor.from(authentication), request)
        if (result !is CyberdefTicketResult.Ok) return respond(result)
        val created = result.value
        return when (created.outcome) {
            CyberdefTicketService.OUTCOME_CREATED -> HttpResponse.created(created)
            CyberdefTicketService.OUTCOME_DUPLICATE, CyberdefTicketService.OUTCOME_WOULD_CREATE -> HttpResponse.ok(created)
            else -> HttpResponse.status<Any>(statusFor(created.errorCode)).body(created)
        }
    }

    @Get("/{id}/notifications")
    @Secured("ADMIN", "CYBERDEF")
    open fun notifications(@PathVariable id: Long, authentication: Authentication): HttpResponse<*> =
        respond(queries.notifications(CyberdefTicketActor.from(authentication), id))

    @Post("/{id}/notifications/resend")
    @Secured("ADMIN", "CYBERDEF")
    open fun resend(@PathVariable id: Long, authentication: Authentication): HttpResponse<*> =
        respond(service.resendFailed(CyberdefTicketActor.from(authentication), id))

    private fun respond(result: CyberdefTicketResult<*>): HttpResponse<*> = when (result) {
        is CyberdefTicketResult.Ok -> HttpResponse.ok(result.value)
        is CyberdefTicketResult.Failed ->
            HttpResponse.status<Any>(statusFor(result.code)).body(mapOf("error" to result.code, "message" to result.message))
    }

    private fun statusFor(code: String?): HttpStatus = when (code) {
        CyberdefTicketResult.NOT_FOUND -> HttpStatus.NOT_FOUND
        CyberdefTicketResult.FORBIDDEN, CyberdefTicketWorkflow.FORBIDDEN -> HttpStatus.FORBIDDEN
        CyberdefTicketResult.VERSION_CONFLICT, CyberdefTicketWorkflow.INVALID_TRANSITION -> HttpStatus.CONFLICT
        CyberdefTicketResult.UNROUTABLE_ACCOUNT, CyberdefTicketResult.RECIPIENT_NOT_ALLOWED -> HttpStatus.UNPROCESSABLE_ENTITY
        CyberdefTicketResult.RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS
        CyberdefTicketResult.INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
        else -> HttpStatus.BAD_REQUEST
    }

    private fun badRequest(message: String): HttpResponse<*> =
        HttpResponse.badRequest(mapOf("error" to CyberdefTicketResult.VALIDATION_ERROR, "message" to message))

    /** Comma lists and repeated params both work; any unknown value fails the whole filter. */
    private fun <E : Enum<E>> parseEnums(raw: List<String>?, entries: List<E>): Set<E>? {
        val values = raw.orEmpty().flatMap { it.split(',') }.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val parsed = values.map { value -> entries.firstOrNull { it.name == value } ?: return null }
        return parsed.toSet()
    }
}
