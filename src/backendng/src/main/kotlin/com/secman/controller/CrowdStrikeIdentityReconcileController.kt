package com.secman.controller

import com.secman.dto.CrowdStrikeIdentityReconcileRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import jakarta.validation.Valid
import org.slf4j.LoggerFactory

@Controller("/api/crowdstrike/identity-reconcile")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class CrowdStrikeIdentityReconcileController(
    private val service: com.secman.service.CrowdStrikeRepairRequestService,
    private val candidates: com.secman.service.CrowdStrikeIdentityCandidateService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Get("/requests/{requestId}")
    open fun requestStatus(@PathVariable requestId: String, authentication: Authentication): HttpResponse<*> = try {
        HttpResponse.ok(service.status(requestId, authentication))
    } catch (e: HttpStatusException) {
        HttpResponse.status<Any>(e.status)
    } catch (e: IllegalArgumentException) {
        HttpResponse.badRequest(mapOf("error" to "A valid UUID request ID is required"))
    } catch (e: Exception) {
        log.error("Falcon repair status actor={} requestId={} outcome=failed",
            authentication.name.replace('\r', '_').replace('\n', '_'), requestId.replace('\r', '_').replace('\n', '_'), e)
        HttpResponse.serverError(mapOf("error" to "Repair status could not be retrieved; outcome remains unconfirmed"))
    }

    @Get("/candidates")
    open fun discover(@QueryValue(defaultValue = "0") afterId: Long,
        @QueryValue(defaultValue = "50") limit: Int, @QueryValue throughId: Long?,
        authentication: Authentication): HttpResponse<*> = try {
        HttpResponse.ok(candidates.candidates(afterId, limit, throughId, authentication))
    } catch (e: HttpStatusException) {
        HttpResponse.status<Any>(e.status)
    } catch (e: IllegalArgumentException) {
        HttpResponse.badRequest(mapOf("error" to "Invalid candidate page; afterId/throughId >= 0 and limit 1..100 required"))
    } catch (e: Exception) {
        log.error("Falcon identity discovery actor={} afterId={} outcome=failed",
            authentication.name.replace('\r', '_').replace('\n', '_'), afterId, e)
        HttpResponse.serverError(mapOf("error" to "Identity candidates could not be retrieved"))
    }

    @Post
    open fun reconcile(@Body @Valid request: CrowdStrikeIdentityReconcileRequest, authentication: Authentication): HttpResponse<*> = try {
        val status = service.execute(request, authentication)
        if (status.report != null) HttpResponse.ok(status.report)
        else HttpResponse.status<Map<String, Any?>>(HttpStatus.valueOf(if (status.state == "RUNNING") 409 else status.httpStatus))
            .body(mapOf("requestId" to status.requestId, "assetId" to status.assetId, "aid" to status.aid,
                "errorCode" to if (status.state == "RUNNING") "REQUEST_IN_PROGRESS" else status.errorCode,
                "error" to if (status.state == "RUNNING") "This request is already running; query its status instead of resubmitting" else status.message))
    } catch (e: HttpStatusException) {
        HttpResponse.status<Any>(e.status)
    } catch (e: IllegalArgumentException) {
        log.warn("Falcon identity repair actor={} assetId={} outcome=rejected-preserved",
            authentication.name.replace('\r', '_').replace('\n', '_'), request.assetId, e)
        val conflict = e as? com.secman.service.CrowdStrikeIdentityConflict
        HttpResponse.status<Map<String, String>>(HttpStatus.CONFLICT).body(mapOf(
            "errorCode" to (conflict?.code ?: "IDENTITY_REJECTED"),
            "error" to (conflict?.message ?: "Invalid identity-repair request; existing inventory was preserved")))
    } catch (e: Exception) {
        log.error("Falcon identity repair actor={} assetId={} outcome=failed-preserved",
            authentication.name.replace('\r', '_').replace('\n', '_'), request.assetId, e)
        HttpResponse.serverError(mapOf("error" to "Identity repair outcome could not be confirmed; query its request status."))
    }
}
