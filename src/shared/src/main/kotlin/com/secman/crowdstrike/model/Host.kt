package com.secman.crowdstrike.model

import java.time.Instant

/**
 * Represents a system being monitored for vulnerabilities
 */
data class Host(
    val hostname: String,
    val hostId: String? = null,
    val operatingSystem: String? = null,
    val ipAddress: String? = null,
    val lastSeen: Instant? = null,
    val vulnerabilities: List<Vulnerability> = emptyList()
) {
    init {
        require(hostname.isNotBlank()) { "Hostname cannot be blank" }
        require(hostname.matches(Regex("^[a-zA-Z0-9.-]+$"))) {
            "Hostname must contain only letters, numbers, hyphens, and periods, got: $hostname"
        }
    }

    fun vulnerabilitiesBySeverity(): Map<Severity, Int> =
        vulnerabilities.groupingBy { it.severity }.eachCount()
}
