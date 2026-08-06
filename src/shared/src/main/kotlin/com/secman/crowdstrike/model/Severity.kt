package com.secman.crowdstrike.model

/**
 * Vulnerability severity levels
 */
enum class Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun fromString(value: String): Severity = try {
            valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            MEDIUM  // Default to MEDIUM for unknown values
        }
    }
}
