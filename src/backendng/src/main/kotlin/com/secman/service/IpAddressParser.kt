package com.secman.service

import com.secman.domain.IpRangeType
import jakarta.inject.Singleton
import org.apache.commons.net.util.SubnetUtils

/**
 * Utility for parsing and validating IP addresses in various formats
 *
 * Supports:
 * - Single IPv4 addresses (192.168.1.100)
 * - CIDR notation (192.168.1.0/24)
 * - Dash ranges (192.168.1.1-192.168.1.100)
 */
@Singleton
class IpAddressParser {

    /**
     * Result of IP address parsing
     */
    data class IpParseResult(
        val rangeType: IpRangeType,
        val originalInput: String,
        val startIpNumeric: Long,
        val endIpNumeric: Long,
        val ipCount: Long
    )

    /**
     * Parse IP address string and determine type and range
     *
     * @param ipAddress IP address string (single, CIDR, or dash range)
     * @return IpParseResult with parsed data
     * @throws IllegalArgumentException if format is invalid
     */
    fun parse(ipAddress: String): IpParseResult {
        val trimmed = ipAddress.trim()

        return when {
            trimmed.contains('/') -> parseCidr(trimmed)
            trimmed.contains('-') -> parseDashRange(trimmed)
            else -> parseSingleIp(trimmed)
        }
    }

    /**
     * Validate IP address format without parsing
     *
     * @param ipAddress IP address string
     * @return true if valid format
     */
    fun isValid(ipAddress: String): Boolean {
        return try {
            parse(ipAddress)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Convert IPv4 address string to numeric representation (Long)
     *
     * Example: "192.168.1.100" -> 3232235876
     *
     * @param ip IPv4 address string
     * @return numeric representation
     * @throws IllegalArgumentException if invalid format
     */
    fun ipToNumeric(ip: String): Long {
        val parts = ip.split('.')
        if (parts.size != 4) {
            throw IllegalArgumentException("Invalid IPv4 format: must have 4 octets")
        }

        var result = 0L
        for (part in parts) {
            val octet = part.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid IPv4 format: non-numeric octet '$part'")

            if (octet < 0 || octet > 255) {
                throw IllegalArgumentException("Invalid IPv4 format: octet $octet out of range (0-255)")
            }

            result = (result shl 8) or octet.toLong()
        }
        return result
    }

    /**
     * Convert numeric representation back to IPv4 string
     *
     * Example: 3232235876 -> "192.168.1.100"
     *
     * @param numeric numeric representation
     * @return IPv4 address string
     */
    fun numericToIp(numeric: Long): String {
        val octet1 = (numeric shr 24) and 0xFF
        val octet2 = (numeric shr 16) and 0xFF
        val octet3 = (numeric shr 8) and 0xFF
        val octet4 = numeric and 0xFF
        return "$octet1.$octet2.$octet3.$octet4"
    }

    /**
     * Parse single IP address
     */
    private fun parseSingleIp(ip: String): IpParseResult {
        val numeric = ipToNumeric(ip)
        return IpParseResult(
            rangeType = IpRangeType.SINGLE,
            originalInput = ip,
            startIpNumeric = numeric,
            endIpNumeric = numeric,
            ipCount = 1
        )
    }

    /**
     * Parse CIDR notation (e.g., 192.168.1.0/24)
     */
    private fun parseCidr(cidr: String): IpParseResult {
        try {
            // Apache Commons Net SubnetUtils for CIDR parsing
            val subnetUtils = SubnetUtils(cidr)
            subnetUtils.isInclusiveHostCount = true // Include network and broadcast addresses

            val info = subnetUtils.info
            val startIp = info.lowAddress ?: info.networkAddress
            val endIp = info.highAddress ?: info.broadcastAddress

            val startNumeric = ipToNumeric(startIp)
            val endNumeric = ipToNumeric(endIp)
            val count = info.addressCountLong

            return IpParseResult(
                rangeType = IpRangeType.CIDR,
                originalInput = cidr,
                startIpNumeric = startNumeric,
                endIpNumeric = endNumeric,
                ipCount = count
            )
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid CIDR format: ${e.message}", e)
        }
    }

    /**
     * Parse dash range (e.g., 192.168.1.1-192.168.1.100)
     */
    private fun parseDashRange(range: String): IpParseResult {
        val parts = range.split('-', limit = 2)
        if (parts.size != 2) {
            throw IllegalArgumentException("Invalid dash range format: must be 'IP1-IP2'")
        }

        val startIp = parts[0].trim()
        val endIp = parts[1].trim()

        val startNumeric = ipToNumeric(startIp)
        val endNumeric = ipToNumeric(endIp)

        if (startNumeric > endNumeric) {
            throw IllegalArgumentException("Invalid range: start IP ($startIp) must be <= end IP ($endIp)")
        }

        val count = endNumeric - startNumeric + 1

        // Validate range size (max Class B: 65,536 IPs)
        if (count > 65536) {
            throw IllegalArgumentException("Range too large: $count IPs (max 65,536 for dash ranges)")
        }

        return IpParseResult(
            rangeType = IpRangeType.DASH_RANGE,
            originalInput = range,
            startIpNumeric = startNumeric,
            endIpNumeric = endNumeric,
            ipCount = count
        )
    }
}
