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
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import java.time.Duration

/**
 * REST Controller for Materialized View Refresh Operations
 *
 * Provides endpoints to:
 * - Trigger manual refresh of outdated assets materialized view
 * - Stream real-time progress updates via Server-Sent Events (SSE)
 * - Get current refresh job status
 *
 * Feature: 034-outdated-assets
 * Task: T046-T057
 * User Story: US3 - Manual Refresh (P2)
 * Spec reference: spec.md FR-014, FR-015, FR-017
 */
@Controller("/api/materialized-view-refresh")
@Secured("ADMIN")  // Only ADMIN can trigger refreshes
class MaterializedViewRefreshController(
    private val refreshService: MaterializedViewRefreshService
) {

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
     *
     * Task: T046-T049
     * Spec reference: FR-014
     */
    @Post("/trigger")
    fun triggerRefresh(authentication: Authentication): HttpResponse<MaterializedViewRefreshJob> {
        val username = authentication.name

        // Trigger async refresh (returns immediately)
        val job = refreshService.triggerAsyncRefresh("Manual refresh by $username")

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
     * Access: ADMIN only
     *
     * Response: text/event-stream with RefreshProgressEvent objects
     *
     * Task: T050-T053
     * Spec reference: FR-015
     */
    @Get(value = "/progress", produces = [MediaType.TEXT_EVENT_STREAM])
    fun streamProgress(): Publisher<Event<RefreshProgressEvent>> {
        // Create SSE event stream from Flux
        return refreshService.getProgressStream()
            .map { event ->
                Event.of(event)
                    .id(event.jobId.toString())
                    .name("progress")
            }
            .doOnSubscribe {
                // Log subscription
                println("Client subscribed to refresh progress stream")
            }
            .doOnCancel {
                // Log unsubscription
                println("Client unsubscribed from refresh progress stream")
            }
    }

    /**
     * GET /api/materialized-view-refresh/status
     *
     * Get current refresh job status (if any job is running)
     *
     * Access: ADMIN only
     *
     * Response:
     * - 200 OK: Current job details
     * - 204 No Content: No refresh currently running
     * - 403 Forbidden: User lacks ADMIN role
     *
     * Task: T054-T055
     * Spec reference: FR-017
     */
    @Get("/status")
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
     * Access: ADMIN only
     *
     * Response:
     * - 200 OK: List of recent refresh jobs
     * - 403 Forbidden: User lacks ADMIN role
     *
     * Task: T056-T057
     * Spec reference: FR-016
     */
    @Get("/history")
    fun getRefreshHistory(): HttpResponse<List<MaterializedViewRefreshJob>> {
        val history = refreshService.getRecentJobs(limit = 10)

        return HttpResponse.ok(history)
    }
}
