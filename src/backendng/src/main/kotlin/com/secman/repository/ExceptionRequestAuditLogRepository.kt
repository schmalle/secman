package com.secman.repository

import com.secman.domain.AuditEventType
import com.secman.domain.ExceptionRequestAuditLog
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

/**
 * Repository for ExceptionRequestAuditLog entity operations.
 *
 * IMPORTANT: This repository is READ-ONLY and INSERT-ONLY.
 * Audit logs are immutable - no UPDATE or DELETE operations are provided.
 *
 * Retention policy: Permanent (manual cleanup after 7 years per compliance requirements)
 *
 * Related to: Feature 031-vuln-exception-approval (FR-026b - Audit logging)
 */
@Repository
interface ExceptionRequestAuditLogRepository : JpaRepository<ExceptionRequestAuditLog, Long> {

    /**
     * Find all audit log entries for a specific exception request
     * Ordered by timestamp ascending (chronological order)
     *
     * @param requestId ID of the exception request
     * @return List of audit logs for this request
     */
    fun findByRequestIdOrderByTimestampAsc(requestId: Long): List<ExceptionRequestAuditLog>

    /**
     * Find audit logs by event type and after a specific timestamp
     * Used for compliance reporting and analytics
     *
     * @param eventType Type of event to filter by
     * @param timestampAfter Minimum timestamp
     * @return List of audit logs matching criteria
     */
    fun findByEventTypeAndTimestampAfter(
        eventType: AuditEventType,
        timestampAfter: LocalDateTime
    ): List<ExceptionRequestAuditLog>

    /**
     * Find audit logs by actor (user who performed the action)
     * Used for user activity audits
     *
     * @param actorUserId ID of the user who performed actions
     * @return List of audit logs for this user
     */
    fun findByActorUserId(actorUserId: Long): List<ExceptionRequestAuditLog>

    /**
     * Find audit logs within a time range
     * Used for compliance reports and historical analysis
     *
     * @param startTime Start of time range
     * @param endTime End of time range
     * @return List of audit logs in this time range
     */
    fun findByTimestampBetween(
        startTime: LocalDateTime,
        endTime: LocalDateTime
    ): List<ExceptionRequestAuditLog>

    /**
     * Count audit logs for a specific request
     * Used for verification of complete audit trail
     *
     * @param requestId ID of the exception request
     * @return Count of audit log entries
     */
    fun countByRequestId(requestId: Long): Long

    // Note: No update() or delete() methods - audit logs are immutable
    // Only save() inherited from JpaRepository is allowed for INSERT operations
}
