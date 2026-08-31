package com.secman.controller

import com.secman.domain.MaterializedViewRefreshJob
import com.secman.domain.RefreshProgressEvent
import com.secman.service.MaterializedViewRefreshService
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.sse.Event
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * REST Controller for Materialized View Refresh Operations
 *
 * Provides endpoints to:
 * - Trigger manual refresh of outdated assets materialized view
 * - Stream real-time progress updates via Server-Sent Events (SSE)
 * - Get current refresh job status
 */
@Controller("/api/materialized-view-refresh")
@Secured(SecurityRule.IS_AUTHENTICATED)
class MaterializedViewRefreshController(
    private val refreshService: MaterializedViewRefreshService
) {
    private val logger = LoggerFactory.getLogger(MaterializedViewRefreshController::class.java)

    /**
     * POST /api/materialized-view-refresh/trigger
     *
     * Triggers an asynchronous refresh of the outdated assets materialized view
     * Returns immediately with job details
     *
     * Access: ADMIN only
     *
     * Response:
     * - 200 OK: Refresh job created successfully
     * - 409 Conflict: A refresh is already running
     * - 403 Forbidden: User lacks ADMIN role
     */
    @Post("/trigger")
    @Secured("ADMIN")
    fun triggerRefresh(authentication: Authentication): HttpResponse<MaterializedViewRefreshJob> {
        val username = authentication.name

        // Trigger async refresh (returns immediately). bypassCooldown=true: an explicit
        // admin request must never be silently deferred by the debounce cooldown that
        // applies to other callers (e.g. CrowdStrike import sub-batches).
        val job = refreshService.triggerAsyncRefresh("Manual refresh by $username", bypassCooldown = true)

        return HttpResponse.ok(job)
    }

    /**
     * GET /api/materialized-view-refresh/progress
     *
     * Server-Sent Events (SSE) endpoint for real-time refresh progress updates
     *
     * Streams RefreshProgressEvent objects as they occur during refresh
     * Clients can listen to this stream to show live progress indicators
     *
     * Access: ADMIN, VULN
     *
     * Response: text/event-stream with RefreshProgressEvent objects
     */
    @Get(value = "/progress", produces = [MediaType.TEXT_EVENT_STREAM])
    @Secured("ADMIN", "VULN")
    fun streamProgress(): Publisher<Event<RefreshProgressEvent>> {
        // Create SSE event stream from Flux
        return refreshService.getProgressStream()
            .map { event ->
                Event.of(event)
                    .id(event.jobId.toString())
                    .name("progress")
            }
            .doOnSubscribe {
                logger.debug("Client subscribed to refresh progress stream")
            }
            .doOnCancel {
                logger.debug("Client unsubscribed from refresh progress stream")
            }
    }

    /**
     * GET /api/materialized-view-refresh/status
     *
     * Get current refresh job status (if any job is running)
     *
     * Access: ADMIN, VULN
     *
     * Response:
     * - 200 OK: Current job details
     * - 204 No Content: No refresh currently running
     * - 403 Forbidden: User lacks ADMIN/VULN role
     */
    @Get("/status")
    @Secured("ADMIN", "VULN")
    fun getRefreshStatus(): HttpResponse<MaterializedViewRefreshJob> {
        val runningJob = refreshService.getCurrentRunningJob()

        return if (runningJob != null) {
            HttpResponse.ok(runningJob)
        } else {
            HttpResponse.noContent()
        }
    }

    /**
     * GET /api/materialized-view-refresh/history
     *
     * Get recent refresh job history (last 10 jobs)
     *
     * Access: ADMIN, VULN
     *
     * Response:
     * - 200 OK: List of recent refresh jobs
     * - 403 Forbidden: User lacks ADMIN/VULN role
     */
    @Get("/history")
    @Secured("ADMIN", "VULN")
    fun getRefreshHistory(): HttpResponse<List<MaterializedViewRefreshJob>> {
        val history = refreshService.getRecentJobs(limit = 10)

        return HttpResponse.ok(history)
    }
}
