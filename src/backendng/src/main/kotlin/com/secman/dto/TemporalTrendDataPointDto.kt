package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

/**
 * DTO for a single data point in temporal vulnerability trend analysis
 *
 * Represents vulnerability counts for a specific date with severity breakdown.
 * Used for time-series visualization in line charts.
 *
 * Feature: 036-vuln-stats-lense
 * Task: T046 [US4]
 * Spec reference: spec.md FR-009, FR-010
 * User Story: US4 - View Temporal Trends (P4)
 * Data model: data-model.md Section "TemporalTrendDataPointDto"
 */
@Serdeable
data class TemporalTrendDataPointDto(
    /**
     * Date for this data point (format: YYYY-MM-DD)
     */
    val date: LocalDate,

    /**
     * Total vulnerability count across all severity levels for this date
     */
    val totalCount: Long,

    /**
     * Count of CRITICAL severity vulnerabilities
     */
    val criticalCount: Long,

    /**
     * Count of HIGH severity vulnerabilities
     */
    val highCount: Long,

    /**
     * Count of MEDIUM severity vulnerabilities
     */
    val mediumCount: Long,

    /**
     * Count of LOW severity vulnerabilities
     */
    val lowCount: Long
)
