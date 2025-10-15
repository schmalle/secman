package com.secman.fixtures

import com.secman.domain.IpRangeType
import com.secman.domain.UserMapping
import com.secman.service.IpAddressParser
import java.time.Instant

/**
 * Test fixtures for IP address mapping
 *
 * Provides factory methods for creating test UserMapping entities with IP addresses.
 *
 * Related to: Feature 020 (IP Address Mapping)
 */
object IpMappingTestFixtures {

    private val parser = IpAddressParser()

    /**
     * Create a UserMapping with a single IP address
     */
    fun createSingleIpMapping(
        email: String = "user@example.com",
        ipAddress: String = "192.168.1.100",
        domain: String? = "example.com",
        id: Long? = null
    ): UserMapping {
        val parseResult = parser.parse(ipAddress)
        return UserMapping(
            id = id,
            email = email,
            awsAccountId = null,
            domain = domain,
            ipAddress = ipAddress,
            ipRangeType = parseResult.rangeType,
            ipRangeStart = parseResult.startIpNumeric,
            ipRangeEnd = parseResult.endIpNumeric,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }

    /**
     * Create a UserMapping with a CIDR range
     */
    fun createCidrMapping(
        email: String = "user@example.com",
        cidr: String = "192.168.1.0/24",
        domain: String? = "example.com",
        id: Long? = null
    ): UserMapping {
        val parseResult = parser.parse(cidr)
        return UserMapping(
            id = id,
            email = email,
            awsAccountId = null,
            domain = domain,
            ipAddress = cidr,
            ipRangeType = IpRangeType.CIDR,
            ipRangeStart = parseResult.startIpNumeric,
            ipRangeEnd = parseResult.endIpNumeric,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }

    /**
     * Create a UserMapping with a dash range
     */
    fun createDashRangeMapping(
        email: String = "user@example.com",
        dashRange: String = "192.168.1.1-192.168.1.100",
        domain: String? = "example.com",
        id: Long? = null
    ): UserMapping {
        val parseResult = parser.parse(dashRange)
        return UserMapping(
            id = id,
            email = email,
            awsAccountId = null,
            domain = domain,
            ipAddress = dashRange,
            ipRangeType = IpRangeType.DASH_RANGE,
            ipRangeStart = parseResult.startIpNumeric,
            ipRangeEnd = parseResult.endIpNumeric,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }

    /**
     * Create a UserMapping with both AWS account and IP address
     */
    fun createCombinedMapping(
        email: String = "user@example.com",
        awsAccountId: String = "123456789012",
        ipAddress: String = "192.168.1.100",
        domain: String? = "example.com",
        id: Long? = null
    ): UserMapping {
        val parseResult = parser.parse(ipAddress)
        return UserMapping(
            id = id,
            email = email,
            awsAccountId = awsAccountId,
            domain = domain,
            ipAddress = ipAddress,
            ipRangeType = parseResult.rangeType,
            ipRangeStart = parseResult.startIpNumeric,
            ipRangeEnd = parseResult.endIpNumeric,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }

    /**
     * Create a UserMapping with only AWS account (no IP)
     */
    fun createAwsOnlyMapping(
        email: String = "user@example.com",
        awsAccountId: String = "123456789012",
        domain: String? = "example.com",
        id: Long? = null
    ): UserMapping {
        return UserMapping(
            id = id,
            email = email,
            awsAccountId = awsAccountId,
            domain = domain,
            ipAddress = null,
            ipRangeType = null,
            ipRangeStart = null,
            ipRangeEnd = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }

    /**
     * Create multiple IP mappings for the same user
     */
    fun createMultipleIpMappings(
        email: String = "user@example.com",
        ipAddresses: List<String> = listOf("192.168.1.100", "10.0.0.0/24", "172.16.0.1-172.16.0.100"),
        domain: String? = "example.com"
    ): List<UserMapping> {
        return ipAddresses.map { ip ->
            val parseResult = parser.parse(ip)
            UserMapping(
                id = null,
                email = email,
                awsAccountId = null,
                domain = domain,
                ipAddress = ip,
                ipRangeType = parseResult.rangeType,
                ipRangeStart = parseResult.startIpNumeric,
                ipRangeEnd = parseResult.endIpNumeric,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        }
    }

    /**
     * Create a raw UserMapping without parsing (for testing validation)
     */
    fun createRawMapping(
        email: String = "user@example.com",
        awsAccountId: String? = null,
        domain: String? = null,
        ipAddress: String? = null,
        ipRangeType: IpRangeType? = null,
        ipRangeStart: Long? = null,
        ipRangeEnd: Long? = null,
        id: Long? = null
    ): UserMapping {
        return UserMapping(
            id = id,
            email = email,
            awsAccountId = awsAccountId,
            domain = domain,
            ipAddress = ipAddress,
            ipRangeType = ipRangeType,
            ipRangeStart = ipRangeStart,
            ipRangeEnd = ipRangeEnd,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }
}
