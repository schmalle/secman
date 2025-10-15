package com.secman.service

import com.secman.domain.IpRangeType
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@MicronautTest
class IpAddressParserTest {

    @Inject
    lateinit var parser: IpAddressParser

    // ========== Single IP Parsing Tests ==========

    @Test
    fun `parse single IP address`() {
        val result = parser.parse("192.168.1.100")

        assertEquals(IpRangeType.SINGLE, result.rangeType)
        assertEquals("192.168.1.100", result.originalInput)
        assertEquals(1, result.ipCount)
        assertEquals(result.startIpNumeric, result.endIpNumeric)
    }

    @Test
    fun `parse single IP with leading and trailing whitespace`() {
        val result = parser.parse("  192.168.1.100  ")

        assertEquals(IpRangeType.SINGLE, result.rangeType)
        assertEquals(1, result.ipCount)
    }

    @Test
    fun `parse single IP at boundaries`() {
        val result1 = parser.parse("0.0.0.0")
        assertEquals(0L, result1.startIpNumeric)

        val result2 = parser.parse("255.255.255.255")
        assertEquals(4294967295L, result2.startIpNumeric)
    }

    @Test
    fun `parse single IP with invalid format`() {
        assertThrows<IllegalArgumentException> { parser.parse("192.168.1") }
        assertThrows<IllegalArgumentException> { parser.parse("192.168.1.256") }
        assertThrows<IllegalArgumentException> { parser.parse("192.168.-1.100") }
        assertThrows<IllegalArgumentException> { parser.parse("abc.def.ghi.jkl") }
        assertThrows<IllegalArgumentException> { parser.parse("192.168.1.1.1") }
    }

    // ========== CIDR Parsing Tests ==========

    @Test
    fun `parse CIDR notation slash 24`() {
        val result = parser.parse("192.168.1.0/24")

        assertEquals(IpRangeType.CIDR, result.rangeType)
        assertEquals("192.168.1.0/24", result.originalInput)
        assertEquals(256L, result.ipCount)
        assertEquals("192.168.1.0", parser.numericToIp(result.startIpNumeric))
        assertEquals("192.168.1.255", parser.numericToIp(result.endIpNumeric))
    }

    @Test
    fun `parse CIDR notation slash 16`() {
        val result = parser.parse("10.0.0.0/16")

        assertEquals(IpRangeType.CIDR, result.rangeType)
        assertEquals(65536L, result.ipCount)
        assertEquals("10.0.0.0", parser.numericToIp(result.startIpNumeric))
        assertEquals("10.0.255.255", parser.numericToIp(result.endIpNumeric))
    }

    @Test
    fun `parse CIDR notation slash 32 (single IP)`() {
        val result = parser.parse("192.168.1.100/32")

        assertEquals(IpRangeType.CIDR, result.rangeType)
        assertEquals(1L, result.ipCount)
        assertEquals(result.startIpNumeric, result.endIpNumeric)
    }

    @Test
    fun `parse CIDR notation slash 8`() {
        val result = parser.parse("10.0.0.0/8")

        assertEquals(IpRangeType.CIDR, result.rangeType)
        assertEquals(16777216L, result.ipCount) // 2^24 = 16M IPs
        assertEquals("10.0.0.0", parser.numericToIp(result.startIpNumeric))
        assertEquals("10.255.255.255", parser.numericToIp(result.endIpNumeric))
    }

    @Test
    fun `parse CIDR with invalid prefix`() {
        assertThrows<IllegalArgumentException> { parser.parse("192.168.1.0/33") }
        assertThrows<IllegalArgumentException> { parser.parse("192.168.1.0/-1") }
        assertThrows<IllegalArgumentException> { parser.parse("192.168.1.0/abc") }
    }

    @Test
    fun `parse CIDR with invalid IP`() {
        assertThrows<IllegalArgumentException> { parser.parse("999.999.999.999/24") }
        assertThrows<IllegalArgumentException> { parser.parse("192.168/24") }
    }

    // ========== Dash Range Parsing Tests ==========

    @Test
    fun `parse dash range within Class C`() {
        val result = parser.parse("192.168.1.1-192.168.1.100")

        assertEquals(IpRangeType.DASH_RANGE, result.rangeType)
        assertEquals("192.168.1.1-192.168.1.100", result.originalInput)
        assertEquals(100L, result.ipCount)
        assertEquals("192.168.1.1", parser.numericToIp(result.startIpNumeric))
        assertEquals("192.168.1.100", parser.numericToIp(result.endIpNumeric))
    }

    @Test
    fun `parse dash range single IP (start equals end)`() {
        val result = parser.parse("192.168.1.100-192.168.1.100")

        assertEquals(IpRangeType.DASH_RANGE, result.rangeType)
        assertEquals(1L, result.ipCount)
    }

    @Test
    fun `parse dash range with whitespace`() {
        val result = parser.parse("  192.168.1.1  -  192.168.1.100  ")

        assertEquals(IpRangeType.DASH_RANGE, result.rangeType)
        assertEquals(100L, result.ipCount)
    }

    @Test
    fun `parse dash range crossing subnets`() {
        val result = parser.parse("192.168.1.200-192.168.2.50")

        assertEquals(IpRangeType.DASH_RANGE, result.rangeType)
        assertTrue(result.ipCount > 100)
    }

    @Test
    fun `parse dash range with start greater than end`() {
        val exception = assertThrows<IllegalArgumentException> {
            parser.parse("192.168.1.255-192.168.1.1")
        }
        assertTrue(exception.message!!.contains("start IP") && exception.message!!.contains("must be <="))
    }

    @Test
    fun `parse dash range too large (over 65536 IPs)`() {
        val exception = assertThrows<IllegalArgumentException> {
            parser.parse("10.0.0.0-10.1.0.0")
        }
        assertTrue(exception.message!!.contains("Range too large"))
    }

    @Test
    fun `parse dash range with invalid format`() {
        assertThrows<IllegalArgumentException> { parser.parse("192.168.1.1-") }
        assertThrows<IllegalArgumentException> { parser.parse("-192.168.1.100") }
        assertThrows<IllegalArgumentException> { parser.parse("192.168.1.1--192.168.1.100") }
    }

    @Test
    fun `parse dash range with invalid IPs`() {
        assertThrows<IllegalArgumentException> { parser.parse("192.168.1.256-192.168.1.100") }
        assertThrows<IllegalArgumentException> { parser.parse("192.168.1.1-abc.def.ghi.jkl") }
    }

    // ========== IP to Numeric Conversion Tests ==========

    @Test
    fun `convert IP to numeric`() {
        assertEquals(0L, parser.ipToNumeric("0.0.0.0"))
        assertEquals(16843009L, parser.ipToNumeric("1.1.1.1"))
        assertEquals(3232235876L, parser.ipToNumeric("192.168.1.100"))
        assertEquals(4294967295L, parser.ipToNumeric("255.255.255.255"))
    }

    @Test
    fun `convert numeric to IP`() {
        assertEquals("0.0.0.0", parser.numericToIp(0L))
        assertEquals("1.1.1.1", parser.numericToIp(16843009L))
        assertEquals("192.168.1.100", parser.numericToIp(3232235876L))
        assertEquals("255.255.255.255", parser.numericToIp(4294967295L))
    }

    @Test
    fun `IP to numeric and back is idempotent`() {
        val ips = listOf(
            "0.0.0.0",
            "1.2.3.4",
            "10.0.0.1",
            "192.168.1.100",
            "172.16.0.50",
            "255.255.255.255"
        )

        for (ip in ips) {
            val numeric = parser.ipToNumeric(ip)
            val converted = parser.numericToIp(numeric)
            assertEquals(ip, converted, "IP $ip should convert to numeric and back")
        }
    }

    // ========== Validation Tests ==========

    @Test
    fun `isValid returns true for valid formats`() {
        assertTrue(parser.isValid("192.168.1.100"))
        assertTrue(parser.isValid("10.0.0.0/24"))
        assertTrue(parser.isValid("172.16.0.1-172.16.0.100"))
        assertTrue(parser.isValid("0.0.0.0"))
        assertTrue(parser.isValid("255.255.255.255"))
    }

    @Test
    fun `isValid returns false for invalid formats`() {
        assertFalse(parser.isValid("192.168.1.256"))
        assertFalse(parser.isValid("abc.def.ghi.jkl"))
        assertFalse(parser.isValid("192.168.1"))
        assertFalse(parser.isValid("192.168.1.1.1"))
        assertFalse(parser.isValid("192.168.1.0/33"))
        assertFalse(parser.isValid("192.168.1.255-192.168.1.1")) // start > end
        assertFalse(parser.isValid("10.0.0.0-10.1.0.0")) // too large
    }

    // ========== Edge Cases ==========

    @Test
    fun `parse empty string`() {
        assertThrows<IllegalArgumentException> { parser.parse("") }
    }

    @Test
    fun `parse whitespace only`() {
        assertThrows<IllegalArgumentException> { parser.parse("   ") }
    }

    @Test
    fun `parse with multiple dashes`() {
        // Should fail because split limit=2 creates invalid second part
        assertThrows<IllegalArgumentException> { parser.parse("192.168.1.1-192.168.1.50-192.168.1.100") }
    }

    @Test
    fun `parse CIDR with multiple slashes`() {
        assertThrows<IllegalArgumentException> { parser.parse("192.168.1.0/24/32") }
    }

    @Test
    fun `parse private IP ranges`() {
        // Class A private
        val result1 = parser.parse("10.0.0.0/8")
        assertEquals(16777216L, result1.ipCount)

        // Class B private
        val result2 = parser.parse("172.16.0.0/12")
        assertEquals(1048576L, result2.ipCount)

        // Class C private
        val result3 = parser.parse("192.168.0.0/16")
        assertEquals(65536L, result3.ipCount)
    }

    @Test
    fun `parse public IP ranges`() {
        val result1 = parser.parse("8.8.8.8") // Google DNS
        assertEquals(IpRangeType.SINGLE, result1.rangeType)

        val result2 = parser.parse("1.1.1.1") // Cloudflare DNS
        assertEquals(IpRangeType.SINGLE, result2.rangeType)
    }
}
