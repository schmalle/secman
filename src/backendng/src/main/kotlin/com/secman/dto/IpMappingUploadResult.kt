package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Response DTO for IP mapping upload operation (CSV/Excel)
 *
 * Provides detailed feedback on import results including counts and skipped rows.
 * Format: "X imported, Y skipped"
 *
 * Related to: Feature 020-i-want-to (IP Address Mapping)
 *
 * @property message Human-readable summary message
 * @property imported Number of IP mappings successfully imported
 * @property skipped Number of rows skipped due to validation errors
 * @property errors List of skipped row details (row number + reason)
 */
@Serdeable
data class IpMappingUploadResult(
    val message: String,
    val imported: Int,
    val skipped: Int,
    val errors: List<SkippedRowDetail> = emptyList()
) {
    companion object {
        /**
         * Create successful upload result
         */
        fun success(imported: Int, skipped: Int = 0, errors: List<SkippedRowDetail> = emptyList()): IpMappingUploadResult {
            val message = if (skipped > 0) {
                "Successfully imported $imported IP mappings, $skipped skipped"
            } else {
                "Successfully imported $imported IP mappings"
            }
            return IpMappingUploadResult(message, imported, skipped, errors)
        }

        /**
         * Create error result
         */
        fun error(message: String): IpMappingUploadResult {
            return IpMappingUploadResult(message, 0, 0, emptyList())
        }
    }
}
