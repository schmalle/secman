package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Pre-flight count summary for cascade deletion warning
 *
 * Purpose: Response for GET /api/assets/{id}/cascade-summary endpoint
 * Provides warning to users about scope of cascade deletion before confirmation
 *
 * Related Requirements:
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
