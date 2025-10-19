package com.secman.dto

/**
 * Standardized response DTO for import operations
 * Features: 013-csv-based-user-mapping-upload, 016-csv-based-user-mapping-upload, 029-asset-bulk-operations
 *
 * Purpose: Consistent import result format across all import features
 * Provides summary of import operation with counts and error details
 *
 * Related Requirements:
 * - FR-023: Provide import summary with imported/skipped counts and error messages
 * - Contract: contracts/asset-import.yaml
 *
 * Usage:
 * - Return from POST /api/import/upload-assets-xlsx
 * - imported: Count of successfully imported records
 * - skipped: Count of skipped rows (duplicates, validation errors)
 * - errors: List of error messages (limited to first 20 for performance)
 * - assetsCreated: Specific to asset imports (number of new assets)
 * - assetsUpdated: Specific to asset imports (always 0 - feature skips duplicates)
 */
data class ImportResult(
    val message: String,
    val imported: Int,
    val skipped: Int,
    val assetsCreated: Int = 0,
    val assetsUpdated: Int = 0,
    val errors: List<String> = emptyList()
)
