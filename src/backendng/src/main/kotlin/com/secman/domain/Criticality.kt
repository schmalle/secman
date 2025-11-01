package com.secman.domain

/**
 * Security criticality classification levels for assets and workgroups.
 *
 * Enum ordering is significant: CRITICAL (highest priority) to LOW (lowest priority).
 * The ordinal values are used for comparison operations via isHigherThan().
 *
 * Feature 039: Asset and Workgroup Criticality Classification
 */
enum class Criticality {
    /** Critical severity - requires immediate attention (e.g., production systems, sensitive data) */
    CRITICAL,

    /** High severity - requires urgent attention (e.g., important business systems) */
    HIGH,

    /** Medium severity - standard business priority (default for workgroups) */
    MEDIUM,

    /** Low severity - minimal business impact (e.g., test/development systems) */
    LOW,

    /** Not applicable - no criticality classification assigned */
    NA;

    /**
     * Returns the human-readable display name for this criticality level.
     *
     * @return The uppercase name of the enum constant (e.g., "CRITICAL", "HIGH")
     */
    fun displayName(): String = if (this == NA) "N/A" else name

    /**
     * Returns the Bootstrap 5 color class for UI rendering.
     *
     * @return Bootstrap color class name without "bg-" or "text-" prefix
     */
    fun bootstrapColor(): String = when (this) {
        CRITICAL -> "danger"   // Red - immediate action required
        HIGH -> "warning"      // Orange - urgent attention needed
        MEDIUM -> "info"       // Blue - standard priority
        LOW -> "secondary"     // Gray - minimal priority
        NA -> "light"          // Light gray - no classification
    }

    /**
     * Returns the Bootstrap Icons class name for accessibility (WCAG 2.1 AA compliance).
     *
     * @return Icon class name from Bootstrap Icons library
     */
    fun icon(): String = when (this) {
        CRITICAL -> "exclamation-triangle-fill"  // Filled triangle for critical alerts
        HIGH -> "exclamation-circle-fill"        // Filled circle for high priority
        MEDIUM -> "info-circle-fill"             // Info circle for medium priority
        LOW -> "check-circle-fill"               // Check circle for low priority
        NA -> "dash-circle"                      // Dash circle for no classification
    }

    /**
     * Compares this criticality level with another to determine priority.
     *
     * Uses enum ordinal values where lower ordinal = higher priority:
     * CRITICAL (0) > HIGH (1) > MEDIUM (2) > LOW (3)
     *
     * @param other The criticality level to compare against
     * @return true if this level is higher priority than the other, false otherwise
     *
     * @see ordinal
     */
    fun isHigherThan(other: Criticality): Boolean = this.ordinal < other.ordinal
}
