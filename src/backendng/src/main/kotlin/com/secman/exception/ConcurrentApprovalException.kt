package com.secman.exception

import com.secman.domain.ExceptionRequestStatus
import java.time.LocalDateTime

/**
 * Exception thrown when multiple reviewers attempt to approve/reject the same
 * exception request simultaneously (optimistic locking failure).
 *
 * Implements the "first-approver-wins" concurrency pattern:
 * - First reviewer's action commits successfully
 * - Subsequent reviewers receive this exception with details of who won
 *
 * Maps to HTTP 409 Conflict in controller layer.
 *
 * Feature: 031-vuln-exception-approval (FR-024b - Concurrency control)
 * Reference: quickstart.md lines 80-91, research.md lines 101-110
 *
 * @param reviewedBy Username of the reviewer who successfully reviewed the request first
 * @param reviewedAt Timestamp when the first reviewer completed their action
 * @param currentStatus Current status of the request after first reviewer's action
 * @param requestId ID of the exception request (for logging/debugging)
 */
class ConcurrentApprovalException(
    val reviewedBy: String,
    val reviewedAt: LocalDateTime,
    val currentStatus: ExceptionRequestStatus,
    val requestId: Long
) : RuntimeException(
    "This request was just reviewed by $reviewedBy at $reviewedAt. " +
    "Current status: $currentStatus. " +
    "Your review was not applied due to concurrent modification. " +
    "Please refresh to see the current state."
) {
    /**
     * Get user-friendly error message for API response.
     *
     * @return Formatted error message suitable for display to users
     */
    fun getUserMessage(): String {
        return "This request was just reviewed by $reviewedBy. " +
               "Current status: $currentStatus. " +
               "Please refresh the page to see the latest state."
    }

    /**
     * Get detailed error message for logging/debugging.
     *
     * @return Detailed message including all context
     */
    fun getDetailedMessage(): String {
        return "Concurrent approval conflict on request $requestId: " +
               "reviewedBy=$reviewedBy, " +
               "reviewedAt=$reviewedAt, " +
               "currentStatus=$currentStatus"
    }
}
