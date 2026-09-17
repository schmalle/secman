package com.secman.crowdstrike.dto

/**
 * Device type classification for CrowdStrike queries
 *
 * Maps to CrowdStrike's product_type_desc field values
 */
enum class DeviceType(val fqlValue: String?) {
    SERVER("Server"),
    DOMAIN_CONTROLLER("Domain Controller"),
    SERVER_FAMILY(null),
    WORKSTATION("Workstation"),
    ALL(null);

    /**
     * Generate FQL filter string for CrowdStrike API
     * Returns null for composite scopes, which must be expanded with [atomicTypes].
     */
    fun toFqlFilter(): String? = fqlValue?.let { "product_type_desc:'$it'" }

    /**
     * Expand a logical import scope into the exact Falcon product types it covers.
     * Exact filters are queried independently and their AIDs are deduplicated by the client.
     */
    fun atomicTypes(): List<DeviceType> = when (this) {
        SERVER_FAMILY -> listOf(SERVER, DOMAIN_CONTROLLER)
        ALL -> listOf(SERVER, DOMAIN_CONTROLLER, WORKSTATION)
        else -> listOf(this)
    }

    /**
     * Get display name for user-facing output
     */
    fun displayName(): String = when (this) {
        SERVER -> "servers"
        DOMAIN_CONTROLLER -> "domain controllers"
        SERVER_FAMILY -> "servers and domain controllers"
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
