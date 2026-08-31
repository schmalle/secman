package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Final result of single asset cascade deletion
 *
 * Purpose: Response for DELETE /api/assets/{id} endpoint on success
 * Provides transparency about scope of deletion and audit trail ID
 *
 * Related Requirements:
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
