package com.secman.domain

/**
 * Audit event type enumeration for exception request lifecycle events.
 *
 * Defines all types of events that are logged to the audit trail.
 *
 * Related to: Feature 031-vuln-exception-approval (FR-026b - Audit logging)
 */
enum class AuditEventType {
    /**
     * New exception request created
     * Context: requester, vulnerability details, scope, reason summary
     */
    REQUEST_CREATED,

    /**
     * Generic status change (fallback for any state transition)
     * Context: old status, new status, actor
     */
    STATUS_CHANGED,

    /**
     * Exception request approved
     * Context: reviewer, review comment (if provided), exception created
     */
    APPROVED,

    /**
     * Exception request rejected
     * Context: reviewer, review comment (required), rejection reason
     */
    REJECTED,

    /**
     * Exception request cancelled by requester
     * Context: cancellation reason, whether auto-approved or pending
     */
    CANCELLED,

    /**
     * Exception request expired (past expiration date)
     * Context: original requester, expiration date
     */
    EXPIRED,

    /**
     * Exception request metadata modified
     * Context: fields changed, old values, new values
     */
    MODIFIED
}
