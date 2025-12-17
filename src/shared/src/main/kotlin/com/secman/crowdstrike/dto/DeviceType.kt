package com.secman.crowdstrike.dto

/**
 * Device type classification for CrowdStrike queries
 *
 * Feature: 055-cli-query-clients
 * Maps to CrowdStrike's product_type_desc field values
 */
enum class DeviceType(val fqlValue: String) {
    SERVER("Server"),
    WORKSTATION("Workstation"),
    ALL("");  // Special case: queries both types

    /**
     * Generate FQL filter string for CrowdStrike API
     * Returns null for ALL (requires special handling - query both types)
     */
    fun toFqlFilter(): String? = when (this) {
        SERVER -> "product_type_desc:'Server'"
        WORKSTATION -> "product_type_desc:'Workstation'"
        ALL -> null
    }

    /**
     * Get display name for user-facing output
     */
    fun displayName(): String = when (this) {
        SERVER -> "servers"
        WORKSTATION -> "workstations"
        ALL -> "all devices"
    }

    companion object {
        /**
         * Parse from string (case-insensitive)
         * @throws IllegalArgumentException if invalid value
         */
        fun fromString(value: String): DeviceType =
            entries.find { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Invalid device type: '$value'. Valid values: ${entries.joinToString { it.name }}"
                )
    }
}
