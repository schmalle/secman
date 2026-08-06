package com.secman.repository

import com.secman.domain.AssetDeletionAuditLog
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

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
     * Count audit logs for a specific asset
     * Used to verify if an asset has been deleted before
     *
     * @param assetId ID of the asset
     * @return Count of audit log entries for this asset
     */
    fun countByAssetId(assetId: Long): Long

    // Note: No update() or delete() methods - audit logs are immutable
    // Only save() inherited from JpaRepository is allowed for INSERT operations
}
