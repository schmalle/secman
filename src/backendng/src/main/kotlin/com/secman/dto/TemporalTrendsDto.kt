package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

/**
 * DTO for temporal vulnerability trend analysis
 *
 * Represents time-series data showing how vulnerability counts change over time.
 * Supports 30, 60, or 90-day trend periods with daily data points.
 *
 * Feature: 036-vuln-stats-lense
 * Task: T047 [US4]
 * Spec reference: spec.md FR-009, FR-010, FR-011
 * User Story: US4 - View Temporal Trends (P4)
 * Data model: data-model.md Section "TemporalTrendsDto"
 */
@Serdeable
data class TemporalTrendsDto(
    /**
     * Start date of the trend period (inclusive)
     */
    val startDate: LocalDate,

    /**
     * End date of the trend period (inclusive, typically today)
     */
    val endDate: LocalDate,

    /**
     * Number of days in the trend period (30, 60, or 90)
     */
    val days: Int,

    /**
     * List of daily data points with vulnerability counts
     * Ordered chronologically from startDate to endDate
     */
    val dataPoints: List<TemporalTrendDataPointDto>
)
