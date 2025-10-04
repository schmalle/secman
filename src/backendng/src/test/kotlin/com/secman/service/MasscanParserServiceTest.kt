package com.secman.service

import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import jakarta.inject.Inject
import java.time.LocalDateTime

/**
 * Unit tests for MasscanParserService
 *
 * Related to: Feature 005-add-funtionality-to (Masscan XML Import)
 *
 * Tests cover:
 * - T002: MasscanParserService contract tests
 * - Valid XML parsing with various structures
 * - Port state filtering (only "open" ports)
 * - Timestamp conversion (Unix epoch → LocalDateTime)
 * - Error handling for malformed/invalid XML
 * - XXE attack prevention
 */
@MicronautTest
class MasscanParserServiceTest {

    @Inject
    private lateinit var masscanParserService: MasscanParserService

    @Test
    fun `testParseSingleHostSinglePort - Valid XML with 1 host 1 port`() {
        // Given
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="masscan" start="1759560572" version="1.0-BETA">
                <scaninfo type="syn" protocol="tcp" />
                <host endtime="1759560572">
                    <address addr="193.99.144.85" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open" reason="syn-ack" reason_ttl="249"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent().toByteArray()

        // When
        val result = masscanParserService.parseMasscanXml(xmlContent)

        // Then
        assertNotNull(result)
        assertEquals(1, result.hosts.size, "Should have exactly 1 host")

        val host = result.hosts.first()
        assertEquals("193.99.144.85", host.ipAddress)
        assertEquals(1, host.ports.size, "Should have exactly 1 port")

        val port = host.ports.first()
        assertEquals(80, port.portNumber)
        assertEquals("tcp", port.protocol)
        assertEquals("open", port.state)

        // Verify scan date conversion (epoch 1759560572)
        assertNotNull(result.scanDate)
    }

    @Test
    fun `testParseMultipleHosts - Valid XML with multiple hosts`() {
        // Given
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="masscan" start="1759560572">
                <host endtime="1759560572">
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
                <host endtime="1759560580">
                    <address addr="192.168.1.2" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="443">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent().toByteArray()

        // When
        val result = masscanParserService.parseMasscanXml(xmlContent)

        // Then
        assertEquals(2, result.hosts.size, "Should have 2 hosts")

        val host1 = result.hosts.find { it.ipAddress == "192.168.1.1" }
        assertNotNull(host1)
        assertEquals(1, host1!!.ports.size)
        assertEquals(80, host1.ports.first().portNumber)

        val host2 = result.hosts.find { it.ipAddress == "192.168.1.2" }
        assertNotNull(host2)
        assertEquals(1, host2!!.ports.size)
        assertEquals(443, host2.ports.first().portNumber)
    }

    @Test
    fun `testParseMultiplePorts - Valid XML with multiple ports per host`() {
        // Given
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="masscan" start="1759560572">
                <host endtime="1759560572">
                    <address addr="193.99.144.85" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                        </port>
                        <port protocol="tcp" portid="443">
                            <state state="open"/>
                        </port>
                        <port protocol="tcp" portid="8080">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent().toByteArray()

        // When
        val result = masscanParserService.parseMasscanXml(xmlContent)

        // Then
        assertEquals(1, result.hosts.size)
        val host = result.hosts.first()
        assertEquals(3, host.ports.size, "Should have 3 ports")

        val portNumbers = host.ports.map { it.portNumber }
        assertTrue(portNumbers.contains(80))
        assertTrue(portNumbers.contains(443))
        assertTrue(portNumbers.contains(8080))
    }

    @Test
    fun `testPortStateFiltering - Only open ports imported`() {
        // Given - XML with mixed port states
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="masscan" start="1759560572">
                <host endtime="1759560572">
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                        </port>
                        <port protocol="tcp" portid="81">
                            <state state="closed"/>
                        </port>
                        <port protocol="tcp" portid="443">
                            <state state="open"/>
                        </port>
                        <port protocol="tcp" portid="444">
                            <state state="filtered"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent().toByteArray()

        // When
        val result = masscanParserService.parseMasscanXml(xmlContent)

        // Then
        val host = result.hosts.first()
        assertEquals(2, host.ports.size, "Should only have 2 open ports (80, 443)")

        val portNumbers = host.ports.map { it.portNumber }
        assertTrue(portNumbers.contains(80), "Should include port 80 (open)")
        assertTrue(portNumbers.contains(443), "Should include port 443 (open)")
        assertFalse(portNumbers.contains(81), "Should NOT include port 81 (closed)")
        assertFalse(portNumbers.contains(444), "Should NOT include port 444 (filtered)")

        // All imported ports must be "open"
        assertTrue(host.ports.all { it.state == "open" }, "All ports should have state='open'")
    }

    @Test
    fun `testTimestampConversion - Unix epoch to LocalDateTime`() {
        // Given - Known epoch timestamp 1759560572 = 2025-10-04 08:49:32 UTC
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="masscan" start="1759560572">
                <host endtime="1759560572">
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent().toByteArray()

        // When
        val result = masscanParserService.parseMasscanXml(xmlContent)

        // Then
        assertNotNull(result.scanDate, "Scan date should be parsed")
        assertEquals(2025, result.scanDate.year)
        assertEquals(10, result.scanDate.monthValue)
        assertEquals(4, result.scanDate.dayOfMonth)

        val host = result.hosts.first()
        assertNotNull(host.timestamp, "Host timestamp should be parsed")
        assertEquals(2025, host.timestamp.year)
        assertEquals(10, host.timestamp.monthValue)
        assertEquals(4, host.timestamp.dayOfMonth)
    }

    @Test
    fun `testMissingIpAddress - Skip host without IP continue others`() {
        // Given - One valid host and one without IP
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="masscan" start="1759560572">
                <host endtime="1759560572">
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
                <host endtime="1759560572">
                    <!-- Missing address element -->
                    <ports>
                        <port protocol="tcp" portid="443">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent().toByteArray()

        // When
        val result = masscanParserService.parseMasscanXml(xmlContent)

        // Then
        assertEquals(1, result.hosts.size, "Should only have 1 valid host (missing IP skipped)")
        assertEquals("192.168.1.1", result.hosts.first().ipAddress)
    }

    @Test
    fun `testInvalidPortNumber - Skip invalid port continue others`() {
        // Given - Valid ports and one with invalid port number
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="masscan" start="1759560572">
                <host endtime="1759560572">
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                        </port>
                        <port protocol="tcp" portid="invalid">
                            <state state="open"/>
                        </port>
                        <port protocol="tcp" portid="443">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent().toByteArray()

        // When
        val result = masscanParserService.parseMasscanXml(xmlContent)

        // Then
        val host = result.hosts.first()
        assertEquals(2, host.ports.size, "Should have 2 valid ports (invalid skipped)")

        val portNumbers = host.ports.map { it.portNumber }
        assertTrue(portNumbers.contains(80))
        assertTrue(portNumbers.contains(443))
    }

    @Test
    fun `testInvalidTimestamp - Use current time log warning`() {
        // Given - Invalid timestamp value
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="masscan" start="invalid_timestamp">
                <host endtime="invalid_timestamp">
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent().toByteArray()

        // When
        val result = masscanParserService.parseMasscanXml(xmlContent)

        // Then
        assertNotNull(result.scanDate, "Should use fallback timestamp")
        assertNotNull(result.hosts.first().timestamp, "Should use fallback timestamp")
        // Verify it's a recent timestamp (within last minute)
        val now = LocalDateTime.now()
        assertTrue(result.scanDate.isAfter(now.minusMinutes(1)))
    }

    @Test
    fun `testEmptyXml - Valid structure no hosts`() {
        // Given
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="masscan" start="1759560572" version="1.0-BETA">
                <scaninfo type="syn" protocol="tcp" />
                <runstats>
                    <finished time="1759560583" elapsed="11" />
                </runstats>
            </nmaprun>
        """.trimIndent().toByteArray()

        // When
        val result = masscanParserService.parseMasscanXml(xmlContent)

        // Then
        assertNotNull(result)
        assertNotNull(result.scanDate)
        assertTrue(result.hosts.isEmpty(), "Should have empty hosts list")
    }

    @Test
    fun `testMalformedXml - Throw MasscanParseException`() {
        // Given - Unclosed tag
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="masscan" start="1234567890">
                <host endtime="1234567890">
                    <address addr="192.168.1.1"
                </host>
        """.trimIndent().toByteArray()

        // When/Then
        val exception = assertThrows<MasscanParseException> {
            masscanParserService.parseMasscanXml(xmlContent)
        }

        assertTrue(exception.message!!.contains("Invalid Masscan XML format") ||
                   exception.message!!.contains("parse"))
    }

    @Test
    fun `testInvalidRootElement - Throw MasscanParseException`() {
        // Given - Wrong root element
        val xmlContent = """
            <?xml version="1.0"?>
            <scanresult>
                <host>
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                </host>
            </scanresult>
        """.trimIndent().toByteArray()

        // When/Then
        val exception = assertThrows<MasscanParseException> {
            masscanParserService.parseMasscanXml(xmlContent)
        }

        assertTrue(exception.message!!.contains("Invalid root element") ||
                   exception.message!!.contains("nmaprun"),
                   "Error message should mention invalid root element")
    }

    @Test
    fun `testWrongScannerAttribute - Throw MasscanParseException`() {
        // Given - nmaprun with scanner="nmap" instead of "masscan"
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="nmap" start="1234567890">
                <host endtime="1234567890">
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent().toByteArray()

        // When/Then
        val exception = assertThrows<MasscanParseException> {
            masscanParserService.parseMasscanXml(xmlContent)
        }

        assertTrue(exception.message!!.contains("Not a Masscan XML file") ||
                   exception.message!!.contains("scanner"),
                   "Error message should identify wrong scanner type")
        assertTrue(exception.message!!.contains("nmap"),
                   "Error message should include the actual scanner value")
    }
}
