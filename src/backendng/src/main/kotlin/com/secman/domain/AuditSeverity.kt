package com.secman.domain

/**
 * Audit severity enumeration for filtering and alerting on audit events.
 */
enum class AuditSeverity {
    /**
     * Normal operational events
     * Examples: Request created, approved, status changes
     */
    INFO,

    /**
     * Warning events that may require attention
     * Examples: Rejections, cancellations, security-related events
     */
    WARN,

    /**
     * Error events indicating system failures
     * Examples: Failed approvals, database errors, validation failures
     */
    ERROR
}
