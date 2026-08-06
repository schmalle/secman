package com.secman.domain

/**
 * Type of IP address mapping
 *
 * - SINGLE: Single IPv4 address (e.g., 192.168.1.100)
 * - CIDR: CIDR notation range (e.g., 192.168.1.0/24)
 * - DASH_RANGE: Dash-separated range (e.g., 192.168.1.1-192.168.1.100)
 */
enum class IpRangeType {
    SINGLE,
    CIDR,
    DASH_RANGE
}
