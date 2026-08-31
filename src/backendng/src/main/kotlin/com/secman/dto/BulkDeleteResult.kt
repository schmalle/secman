package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Response DTO for bulk delete operation with counts
 *
 * Purpose: Response for DELETE /api/assets/bulk endpoint
 * Provides transparency about scope of deletion (cascaded entities)
 *
 * Related Requirements:
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
