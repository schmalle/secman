package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * DTO for vulnerability severity distribution statistics
 *
 * Represents the distribution of vulnerabilities across CVSS severity levels
 * with counts and computed percentages. Used for pie chart visualization.
 *
 * Feature: 036-vuln-stats-lense
 * Task: T021 [US2]
 * Spec reference: spec.md FR-003, FR-004
 * User Story: US2 - View Severity Distribution (P2)
 * Data model: data-model.md Section "SeverityDistributionDto"
 */
@Serdeable
data class SeverityDistributionDto(
    /**
     * Count of CRITICAL severity vulnerabilities
     */
    val critical: Long,

    /**
     * Count of HIGH severity vulnerabilities
     */
    val high: Long,

    /**
     * Count of MEDIUM severity vulnerabilities
     */
    val medium: Long,

    /**
     * Count of LOW severity vulnerabilities
     */
    val low: Long,

    /**
     * Count of vulnerabilities with UNKNOWN or null severity
     */
    val unknown: Long
) {
    /**
     * Total vulnerability count across all severity levels
     */
    val total: Long
        get() = critical + high + medium + low + unknown

    /**
     * Percentage of CRITICAL vulnerabilities (0-100)
     * Returns 0.0 if total is 0 to avoid division by zero
     */
    val criticalPercentage: Double
        get() = if (total > 0) (critical.toDouble() / total * 100) else 0.0

    /**
     * Percentage of HIGH vulnerabilities (0-100)
     * Returns 0.0 if total is 0 to avoid division by zero
     */
    val highPercentage: Double
        get() = if (total > 0) (high.toDouble() / total * 100) else 0.0

    /**
     * Percentage of MEDIUM vulnerabilities (0-100)
     * Returns 0.0 if total is 0 to avoid division by zero
     */
    val mediumPercentage: Double
        get() = if (total > 0) (medium.toDouble() / total * 100) else 0.0

    /**
     * Percentage of LOW vulnerabilities (0-100)
     * Returns 0.0 if total is 0 to avoid division by zero
     */
    val lowPercentage: Double
        get() = if (total > 0) (low.toDouble() / total * 100) else 0.0

    /**
     * Percentage of UNKNOWN vulnerabilities (0-100)
     * Returns 0.0 if total is 0 to avoid division by zero
     */
    val unknownPercentage: Double
        get() = if (total > 0) (unknown.toDouble() / total * 100) else 0.0
}
