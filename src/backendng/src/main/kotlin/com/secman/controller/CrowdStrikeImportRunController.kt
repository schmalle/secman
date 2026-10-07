package com.secman.controller

import com.secman.service.CrowdStrikeImportRunLease
import com.secman.service.ImportCompletionNotifier
import com.secman.service.MaterializedViewRefreshService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.http.annotation.PathVariable
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import org.slf4j.LoggerFactory
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication

@Controller("/api/crowdstrike/servers/import/runs")
@Secured("ADMIN", "VULN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class CrowdStrikeImportRunController(
    private val lease: CrowdStrikeImportRunLease,
    private val notifier: ImportCompletionNotifier,
    private val refreshService: MaterializedViewRefreshService
) {
    private val log = LoggerFactory.getLogger(CrowdStrikeImportRunController::class.java)
    private fun actor(authentication: Authentication) = authentication.name.replace("\r", "").replace("\n", "")

    @Post
    @Synchronized
    open fun start(authentication: Authentication): HttpResponse<*> = try {
        val id = lease.start(authentication.name)
        notifier.beginExplicitRun(id)
        log.info("CrowdStrike import run started: runId={}, actor={}", id, actor(authentication))
        HttpResponse.ok(mapOf("runId" to id))
    } catch (e: IllegalStateException) {
        HttpResponse.status<Map<String, String>>(HttpStatus.CONFLICT)
            .body(mapOf("error" to "An import run is already active"))
    }

    @Post("/{runId}/heartbeat")
    open fun heartbeat(@PathVariable runId: String, authentication: Authentication): HttpResponse<*> = try {
        lease.heartbeat(runId, authentication.name)
        HttpResponse.ok(mapOf("status" to "ACTIVE"))
    } catch (e: IllegalStateException) {
        HttpResponse.status<Map<String, String>>(HttpStatus.CONFLICT)
            .body(mapOf("error" to "Import run is unavailable"))
    }

    @Post("/{runId}/finish")
    @Synchronized
    open fun finish(@PathVariable runId: String, @QueryValue(defaultValue = "false") successful: Boolean,
                    authentication: Authentication): HttpResponse<*> = try {
        lease.requireOwner(runId, authentication.name)
        notifier.finishExplicitRun(runId, successful)
        lease.finish(runId, authentication.name)
        log.info("CrowdStrike import run finished: runId={}, actor={}, successful={}", runId, actor(authentication), successful)
        refreshService.requestDeferredRefresh("CrowdStrike import run ended")
        HttpResponse.ok(mapOf("status" to if (successful) "COMPLETED" else "FAILED"))
    } catch (e: IllegalStateException) {
        HttpResponse.status<Map<String, String>>(HttpStatus.CONFLICT)
            .body(mapOf("error" to "Import run is unavailable"))
    }
}
