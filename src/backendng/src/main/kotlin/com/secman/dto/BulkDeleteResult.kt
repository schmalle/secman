package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Response DTO for bulk delete operation with counts
 * Feature: 029-asset-bulk-operations (User Story 1 - Bulk Delete Assets)
 *
 * Purpose: Response for DELETE /api/assets/bulk endpoint
 * Provides transparency about scope of deletion (cascaded entities)
 *
 * Related Requirements:
 * - FR-004: Display success message showing count of deleted assets
 * - FR-007: Handle cascade deletion of related data (vulnerabilities, scan results)
 * - Contract: contracts/bulk-delete.yaml
 */
@Serdeable
data class BulkDeleteResult(
    val deletedAssets: Int,
    val deletedVulnerabilities: Int,
    val deletedScanResults: Int,
    val message: String
) {
    companion object {
        fun success(assetCount: Int, vulnCount: Int, scanCount: Int): BulkDeleteResult {
            return BulkDeleteResult(
                deletedAssets = assetCount,
                deletedVulnerabilities = vulnCount,
                deletedScanResults = scanCount,
                message = "Successfully deleted $assetCount assets, $vulnCount vulnerabilities, and $scanCount scan results"
            )
        }
    }
}
