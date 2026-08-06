package com.secman.domain

/**
 * Audit severity enumeration for filtering and alerting on audit events.
 *
 * Related to: Feature 031-vuln-exception-approval (FR-026b - Audit logging)
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
