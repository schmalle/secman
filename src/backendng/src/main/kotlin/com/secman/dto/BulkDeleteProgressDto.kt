package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Real-time progress update for bulk operations (SSE stream)
 * Feature: 033-cascade-asset-deletion (User Story 3 - Transactional Bulk Asset Deletion)
 *
 * Purpose: Streamed via SSE in DELETE /api/assets/bulk/stream endpoint
 * Provides real-time feedback during bulk deletion operations
 *
 * Related Requirements:
 * - FR-014: System MUST execute bulk deletions sequentially and stream progress
 * - FR-013: Detailed error messages on failure
 * - Contract: contracts/cascade-delete-api.yaml
 */
@Serdeable
data class BulkDeleteProgressDto(
    val total: Int,
    val completed: Int,
    val currentAssetId: Long,
    val currentAssetName: String,
    val status: BulkDeletionStatus,
    val error: String? = null
)

/**
 * Status of bulk deletion progress
 */
enum class BulkDeletionStatus {
    /**
     * Currently processing an asset
     */
    PROCESSING,

    /**
     * Asset successfully deleted
     */
    SUCCESS,

    /**
     * Asset deletion failed
     */
    FAILED
}
