package com.secman.controller

import com.secman.domain.ExceptionCountChangedEvent
import com.secman.domain.ExceptionRequestStatus
import com.secman.repository.VulnerabilityExceptionRequestRepository
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.sse.Event
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import jakarta.inject.Singleton
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.core.publisher.Sinks.Many
import org.slf4j.LoggerFactory

/**
 * Server-Sent Events (SSE) endpoint for real-time exception badge count updates.
 *
 * **Workflow**:
 * 1. Frontend connects to /api/exception-badge-updates (EventSource)
 * 2. Handler immediately sends current pending count
 * 3. Handler listens for ExceptionCountChangedEvent
 * 4. On event, broadcasts new count to all connected clients
 * 5. Frontend updates badge in real-time
 *
 * **Event Format**:
 * ```
 * event: count-update
 * data: {"pendingCount": 5}
 * ```
 *
 * **Concurrency**:
 * - Thread-safe Sinks.Many with multicast
 * - Automatic cleanup on client disconnect
 * - Replay(1) to ensure new subscribers get latest count
 *
 * Feature: 031-vuln-exception-approval
 * User Story 3: ADMIN Approval Dashboard (P1)
 * Phase 6: Real-Time Badge Updates
 * Reference: spec.md FR-024
 */
@Controller("/api/exception-badge-updates")
@Secured(SecurityRule.IS_AUTHENTICATED)
@Singleton
class ExceptionBadgeUpdateHandler(
    private val exceptionRequestRepository: VulnerabilityExceptionRequestRepository
) : ApplicationEventListener<ExceptionCountChangedEvent> {

    private val logger = LoggerFactory.getLogger(ExceptionBadgeUpdateHandler::class.java)

    /**
     * Multicast sink for broadcasting count updates to all SSE subscribers.
     * - Replay(1): New subscribers immediately receive the latest count
     * - Multicast: Multiple subscribers can receive the same events
     * - onBackpressureBuffer: Buffer events if client is slow
     */
    private val sink: Many<CountUpdateData> = Sinks.many()
        .multicast()
        .onBackpressureBuffer()

    /**
     * SSE endpoint for badge count updates.
     *
     * **Access**: Requires authentication (any authenticated user)
     * **Content-Type**: text/event-stream
     *
     * **Response Stream**:
     * 1. Initial event: Current pending count
     * 2. Subsequent events: Count updates when ExceptionCountChangedEvent fires
     *
     * **Client Usage** (Frontend):
     * ```typescript
     * const eventSource = new EventSource('/api/exception-badge-updates');
     * eventSource.addEventListener('count-update', (event) => {
     *   const data = JSON.parse(event.data);
     *   updateBadge(data.pendingCount);
     * });
     * ```
     *
     * @return Publisher of SSE events with count updates
     */
    @Get(produces = [MediaType.TEXT_EVENT_STREAM])
    fun streamBadgeUpdates(): Publisher<Event<CountUpdateData>> {
        logger.info("New SSE client connected for exception badge updates")

        // Get current pending count from database
        val currentCount = exceptionRequestRepository.countByStatus(ExceptionRequestStatus.PENDING)
        logger.debug("Sending initial count to new subscriber: {}", currentCount)

        // Create initial event with current count
        val initialEvent = Event.of(CountUpdateData(currentCount))
            .name("count-update")

        // Merge initial event with future updates from sink
        return Flux.concat(
            Flux.just(initialEvent),
            sink.asFlux()
                .map { data ->
                    Event.of(data).name("count-update")
                }
                .doOnCancel {
                    logger.info("SSE client disconnected from exception badge updates")
                }
                .doOnError { error ->
                    logger.error("Error in SSE stream for exception badge updates", error)
                }
        )
    }

    /**
     * Event listener for ExceptionCountChangedEvent.
     *
     * Called automatically by Micronaut when the event is published.
     * Broadcasts the new count to all connected SSE clients.
     *
     * **Thread Safety**: Sinks.Many handles concurrent access
     *
     * @param event The count changed event with new count
     */
    override fun onApplicationEvent(event: ExceptionCountChangedEvent) {
        logger.info("Received ExceptionCountChangedEvent: {}", event)

        val data = CountUpdateData(event.newCount)

        // Emit to all subscribers
        val result = sink.tryEmitNext(data)

        if (result.isFailure) {
            logger.error("Failed to emit count update to SSE subscribers: {}", result)
        } else {
            logger.debug("Successfully broadcast count update to all SSE subscribers: {}", data)
        }
    }

    /**
     * Data class for SSE count update events.
     *
     * Serialized to JSON in SSE data field:
     * ```json
     * {"pendingCount": 5}
     * ```
     */
    data class CountUpdateData(
        val pendingCount: Long
    )
}
