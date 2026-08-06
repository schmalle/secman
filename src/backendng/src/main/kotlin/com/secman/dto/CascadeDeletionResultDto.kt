package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Final result of single asset cascade deletion
 * Feature: 033-cascade-asset-deletion (User Story 1 - Delete Asset with All Related Data)
 *
 * Purpose: Response for DELETE /api/assets/{id} endpoint on success
 * Provides transparency about scope of deletion and audit trail ID
 *
 * Related Requirements:
 * - FR-001: System MUST cascade delete vulnerabilities when asset is deleted
 * - FR-002: System MUST cascade delete ASSET-type exceptions
 * - FR-003: System MUST cascade delete vulnerability exception requests
 * - FR-011: Audit log created for every deletion
 * - Contract: contracts/cascade-delete-api.yaml
 */
@Serdeable
data class CascadeDeletionResultDto(
    val assetId: Long,
    val assetName: String,
    val deletedVulnerabilities: Int,
    val deletedExceptions: Int,
    val deletedRequests: Int,
    val auditLogId: Long
) {
    companion object {
        fun success(
            assetId: Long,
            assetName: String,
            vulnCount: Int,
            exceptionCount: Int,
            requestCount: Int,
            auditId: Long
        ): CascadeDeletionResultDto {
            return CascadeDeletionResultDto(
                assetId = assetId,
                assetName = assetName,
                deletedVulnerabilities = vulnCount,
                deletedExceptions = exceptionCount,
                deletedRequests = requestCount,
                auditLogId = auditId
            )
        }
    }
}
