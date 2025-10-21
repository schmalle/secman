package com.secman.domain

/**
 * Exception request status enumeration for vulnerability exception request workflow.
 *
 * State machine transitions:
 * ```
 *     ┌──────────┐
 *     │ PENDING  │
 *     └────┬─────┘
 *          │
 *     ┌────┴────┬─────────┬──────────┐
 *     │         │         │          │
 *     ▼         ▼         ▼          ▼
 * ┌─────────┐ ┌────────┐ ┌────────┐ ┌─────────┐
 * │APPROVED │ │REJECTED│ │CANCELLED│ │EXPIRED  │
 * └────┬────┘ └────────┘ └────────┘ └─────────┘
 *      │         (terminal states)
 *      │
 *      ▼
 * ┌─────────┐
 * │EXPIRED  │
 * └─────────┘
 * ```
 *
 * Related to: Feature 031-vuln-exception-approval
 */
enum class ExceptionRequestStatus {
    /**
     * Request is awaiting ADMIN/SECCHAMPION approval
     * Initial status for regular user requests
     */
    PENDING,

    /**
     * Request has been approved and exception created
     * - Auto-approved for ADMIN/SECCHAMPION requesters
     * - Manually approved by ADMIN/SECCHAMPION reviewers
     * Can transition to: EXPIRED
     */
    APPROVED,

    /**
     * Request has been rejected, no exception created
     * Terminal state - cannot be modified
     */
    REJECTED,

    /**
     * Approved exception has passed its expiration date
     * Terminal state - cannot be modified
     * Transitions automatically via daily scheduled job
     */
    EXPIRED,

    /**
     * Request was cancelled by the requester
     * Terminal state - cannot be modified
     * Only PENDING requests can be cancelled
     */
    CANCELLED;

    /**
     * Check if this status can transition to the given new status
     *
     * Valid transitions:
     * - PENDING → APPROVED, REJECTED, CANCELLED
     * - APPROVED → EXPIRED, CANCELLED (CANCELLED only for auto-approved, checked in service layer)
     * - REJECTED, EXPIRED, CANCELLED → (no transitions, terminal states)
     *
     * @param newStatus The target status
     * @return true if transition is valid, false otherwise
     */
    fun canTransitionTo(newStatus: ExceptionRequestStatus): Boolean {
        return when (this) {
            PENDING -> newStatus in setOf(APPROVED, REJECTED, CANCELLED)
            APPROVED -> newStatus in setOf(EXPIRED, CANCELLED) // CANCELLED for auto-approved only
            REJECTED, EXPIRED, CANCELLED -> false // Terminal states
        }
    }

    /**
     * Check if this status is a terminal state (no further transitions allowed)
     *
     * @return true if terminal, false otherwise
     */
    fun isTerminal(): Boolean {
        return when (this) {
            REJECTED, EXPIRED, CANCELLED -> true
            PENDING, APPROVED -> false
        }
    }

    /**
     * Check if this status represents an active request (not expired or cancelled)
     *
     * @return true if active, false otherwise
     */
    fun isActive(): Boolean {
        return when (this) {
            PENDING, APPROVED -> true
            REJECTED, EXPIRED, CANCELLED -> false
        }
    }
}
