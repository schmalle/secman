package com.secman.repository

import com.secman.domain.ExceptionRequestAuditLog
import io.micronaut.data.annotation.Query
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
     * Nullify the actorUser reference when a user is deleted.
     *
     * Audit logs are immutable as a rule, but the entity FK to users.id has no
     * ON DELETE behavior, so MariaDB blocks user deletion unless this column is
     * cleared. The denormalized actorUsername column preserves the historical
     * actor identity, matching the design intent on ExceptionRequestAuditLog
     * ("Preserved even if user account deleted").
     *
     * Called from UserService.deleteUser. The audit row itself remains intact.
     */
    @Query("UPDATE ExceptionRequestAuditLog a SET a.actorUser = NULL WHERE a.actorUser.id = :userId")
    fun nullifyActorUserForUser(userId: Long): Int

    // Note: No update() or delete() methods - audit logs are immutable
    // Only save() inherited from JpaRepository is allowed for INSERT operations
}
