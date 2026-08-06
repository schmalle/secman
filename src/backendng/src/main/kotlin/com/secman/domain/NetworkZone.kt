package com.secman.domain

/**
 * Network zone classification for assets.
 * Determines the network exposure level of an asset.
 *
 * Used by the CLI port-scan command to identify scannable internet-facing assets.
 */
enum class NetworkZone {
    /** Directly internet-facing with public IP, no firewall */
    EXTERNAL,

    /** In a DMZ, reachable from internet through firewall/NAT */
    DMZ,

    /** Internal network only, not reachable from internet */
    INTERNAL,

    /** Not yet classified (default for existing/new assets) */
    UNKNOWN;

    fun displayName(): String = name

    fun bootstrapColor(): String = when (this) {
        EXTERNAL -> "danger"
        DMZ -> "warning"
        INTERNAL -> "success"
        UNKNOWN -> "secondary"
    }
}
