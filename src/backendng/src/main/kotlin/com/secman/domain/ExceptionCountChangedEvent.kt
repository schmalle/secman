package com.secman.domain

import io.micronaut.context.event.ApplicationEvent

/**
 * Domain event published when the count of pending exception requests changes.
 *
 * Used to notify SSE clients (via ExceptionBadgeUpdateHandler) that the badge count
 * should be refreshed.
 *
 * **Trigger Conditions**:
 * - New request created (count +1)
 * - Request approved (count -1)
 * - Request rejected (count -1)
 * - Request cancelled (count -1)
 *
 * **Event Flow**:
 * 1. Service method completes status transition
 * 2. Service publishes ExceptionCountChangedEvent
 * 3. ExceptionBadgeUpdateHandler receives event
 * 4. Handler broadcasts new count to all SSE subscribers
 * 5. Frontend exceptionBadgeService updates badge
 *
 * Feature: 031-vuln-exception-approval
 * User Story 3: ADMIN Approval Dashboard (P1)
 * Phase 6: Real-Time Badge Updates
 * Reference: spec.md FR-024
 *
 * @property newCount The current count of pending requests after the change
 * @property source The event source (typically the ApplicationEventPublisher)
 */
class ExceptionCountChangedEvent(
    val newCount: Long,
    source: Any
) : ApplicationEvent(source) {

    override fun toString(): String {
        return "ExceptionCountChangedEvent(newCount=$newCount)"
    }
}
