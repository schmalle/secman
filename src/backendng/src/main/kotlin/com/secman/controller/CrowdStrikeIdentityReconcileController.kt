package com.secman.controller

import com.secman.dto.CrowdStrikeIdentityReconcileRequest
import com.secman.service.CrowdStrikeIdentityReconcileService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
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
    private val service: CrowdStrikeIdentityReconcileService,
    private val candidates: com.secman.service.CrowdStrikeIdentityCandidateService
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
        HttpResponse.ok(service.reconcile(request, authentication))
    } catch (e: HttpStatusException) {
        HttpResponse.status<Any>(e.status)
    } catch (e: IllegalArgumentException) {
        log.warn("Falcon identity repair actor={} assetId={} outcome=rejected-preserved",
            authentication.name.replace('\r', '_').replace('\n', '_'), request.assetId, e)
        val resolved = (e as? com.secman.service.CrowdStrikeIdentityConflict)?.code == "ALREADY_RESOLVED"
        HttpResponse.status<Map<String, String>>(HttpStatus.CONFLICT).body(mapOf(
            "errorCode" to if (resolved) "ALREADY_RESOLVED" else "IDENTITY_REJECTED",
            "error" to if (resolved) "Source asset now has one verified binding; no repair was performed." else
                "Identity split could not be verified or stored state changed; stored data was preserved. Review Falcon metadata and preview again."))
    } catch (e: Exception) {
        log.error("Falcon identity repair actor={} assetId={} outcome=failed-preserved",
            authentication.name.replace('\r', '_').replace('\n', '_'), request.assetId, e)
        HttpResponse.serverError(mapOf("error" to "Identity repair failed; stored data was preserved."))
    }
}
