package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

/**
 * DTO for temporal vulnerability trend analysis
 *
 * Represents time-series data showing how vulnerability counts change over time.
 * Supports 30, 60, or 90-day trend periods with daily data points.
 *
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
