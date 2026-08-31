package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Details about a skipped row during vulnerability import
 *
 * Used in VulnerabilityImportResponse to provide granular error reporting.
 *
 * @property row Row number (1-indexed, excluding header row)
 * @property reason Human-readable reason why the row was skipped
 */
@Serdeable
data class SkippedRowDetail(
    val row: Int,
    val reason: String
)
