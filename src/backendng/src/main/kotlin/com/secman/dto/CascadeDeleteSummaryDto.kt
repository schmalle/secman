package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Pre-flight count summary for cascade deletion warning
 * Feature: 033-cascade-asset-deletion (User Story 4 - UI Warning Before Cascade Deletion)
 *
 * Purpose: Response for GET /api/assets/{id}/cascade-summary endpoint
 * Provides warning to users about scope of cascade deletion before confirmation
 *
 * Related Requirements:
 * - FR-012: System MUST perform a pre-flight count of related records
 * - FR-014: System MUST provide detailed error messages (timeout warnings)
 * - Contract: contracts/cascade-delete-api.yaml
 */
@Serdeable
data class CascadeDeleteSummaryDto(
    val assetId: Long,
    val assetName: String,
    val vulnerabilitiesCount: Int,
    val assetExceptionsCount: Int,
    val exceptionRequestsCount: Int,
    val estimatedDurationSeconds: Int,
    val exceedsTimeout: Boolean
)
