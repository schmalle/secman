package com.secman.repository

import com.secman.domain.AssetDeletionAuditLog
import com.secman.domain.DeletionOperationType
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

/**
 * Repository for AssetDeletionAuditLog entity operations.
 *
 * IMPORTANT: This repository is READ-ONLY and INSERT-ONLY.
 * Audit logs are immutable - no UPDATE or DELETE operations are provided.
 *
 * Related to: Feature 033-cascade-asset-deletion (FR-011 - Audit logging)
 */
@Repository
interface AssetDeletionAuditLogRepository : JpaRepository<AssetDeletionAuditLog, Long> {

    /**
     * Find all audit log entries for a specific asset (after deletion)
     * Ordered by timestamp descending (most recent first)
     *
     * @param assetId ID of the deleted asset
     * @return List of audit logs for this asset
     */
    fun findByAssetIdOrderByDeletionTimestampDesc(assetId: Long): List<AssetDeletionAuditLog>

    /**
     * Find audit logs by user who performed the deletion
     * Used for user activity audits
     *
     * @param deletedByUser Username who performed deletions
     * @return List of audit logs for this user
     */
    fun findByDeletedByUserOrderByDeletionTimestampDesc(deletedByUser: String): List<AssetDeletionAuditLog>

    /**
     * Find all audit logs for a bulk operation
     * Used to retrieve all assets deleted in a single bulk operation
     *
     * @param bulkOperationId UUID of the bulk operation
     * @return List of audit logs for this bulk operation
     */
    fun findByBulkOperationIdOrderByDeletionTimestampAsc(bulkOperationId: String): List<AssetDeletionAuditLog>

    /**
     * Find audit logs within a time range
     * Used for compliance reports and historical analysis
     *
     * @param startTime Start of time range
     * @param endTime End of time range
     * @return List of audit logs in this time range
     */
    fun findByDeletionTimestampBetween(
        startTime: LocalDateTime,
        endTime: LocalDateTime
    ): List<AssetDeletionAuditLog>

    /**
     * Find audit logs by operation type (SINGLE or BULK)
     *
     * @param operationType Type of operation
     * @return List of audit logs matching operation type
     */
    fun findByOperationType(operationType: DeletionOperationType): List<AssetDeletionAuditLog>

    /**
     * Count audit logs for a specific asset
     * Used to verify if an asset has been deleted before
     *
     * @param assetId ID of the asset
     * @return Count of audit log entries for this asset
     */
    fun countByAssetId(assetId: Long): Long

    /**
     * Count deletions performed by a specific user
     *
     * @param deletedByUser Username
     * @return Count of deletions by this user
     */
    fun countByDeletedByUser(deletedByUser: String): Long

    /**
     * Find most recent audit logs (for dashboard/monitoring)
     * Ordered by timestamp descending
     *
     * @return Recent audit logs
     */
    fun findTop100OrderByDeletionTimestampDesc(): List<AssetDeletionAuditLog>

    // Note: No update() or delete() methods - audit logs are immutable
    // Only save() inherited from JpaRepository is allowed for INSERT operations
}
