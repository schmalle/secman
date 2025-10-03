package com.secman.service

import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import jakarta.inject.Inject
import java.io.File

/**
 * Unit tests for NmapParserService
 *
 * Tests cover:
 * - T036: XML parsing validation
 * - XXE attack prevention
 * - Error handling for malformed XML
 * - Correct extraction of host and port data
 */
@MicronautTest
class NmapParserServiceTest {

    @Inject
    private lateinit var nmapParserService: NmapParserService

    private fun getTestXmlContent(): String {
        val testFile = File("src/test/resources/nmap-test.xml")
        return testFile.readText()
    }

    @Test
    fun `test parse valid nmap XML successfully`() {
        // Given
        val xmlContent = getTestXmlContent()

        // When
        val result = nmapParserService.parseNmapXml(xmlContent)

        // Then
        assertNotNull(result)
        assertEquals("nmap", result.scanType)
        assertNotNull(result.scanDate)
        assertTrue(result.hosts.isNotEmpty(), "Should have at least one host")

        // Verify host data
        val host = result.hosts.first()
        assertEquals("193.99.144.85", host.ipAddress)
        assertEquals("www.heise.de", host.hostname)

        // Verify ports
        assertTrue(host.ports.isNotEmpty(), "Host should have ports")

        // Check for known ports from test file: 22, 80, 443
        val portNumbers = host.ports.map { it.portNumber }
        assertTrue(portNumbers.contains(22), "Should have port 22 (SSH)")
        assertTrue(portNumbers.contains(80), "Should have port 80 (HTTP)")
        assertTrue(portNumbers.contains(443), "Should have port 443 (HTTPS)")

        // Verify port details for port 80
        val port80 = host.ports.find { it.portNumber == 80 }
        assertNotNull(port80)
        assertEquals("tcp", port80!!.protocol)
        assertEquals("open", port80.state)
        assertEquals("http", port80.service)
    }

    @Test
    fun `test parse XML with multiple hosts`() {
        // Given - XML with 2 hosts
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="nmap" start="1234567890">
                <host>
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                    <hostnames>
                        <hostname name="router.local" type="PTR"/>
                    </hostnames>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                            <service name="http"/>
                        </port>
                    </ports>
                </host>
                <host>
                    <address addr="192.168.1.2" addrtype="ipv4"/>
                    <hostnames>
                        <hostname name="server.local" type="PTR"/>
                    </hostnames>
                    <ports>
                        <port protocol="tcp" portid="443">
                            <state state="open"/>
                            <service name="https"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        // When
        val result = nmapParserService.parseNmapXml(xmlContent)

        // Then
        assertEquals(2, result.hosts.size, "Should parse both hosts")

        val host1 = result.hosts[0]
        assertEquals("192.168.1.1", host1.ipAddress)
        assertEquals("router.local", host1.hostname)

        val host2 = result.hosts[1]
        assertEquals("192.168.1.2", host2.ipAddress)
        assertEquals("server.local", host2.hostname)
    }

    @Test
    fun `test parse XML with host without hostname`() {
        // Given - Host with IP only, no hostname
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="nmap" start="1234567890">
                <host>
                    <address addr="10.0.0.1" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="22">
                            <state state="open"/>
                            <service name="ssh"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        // When
        val result = nmapParserService.parseNmapXml(xmlContent)

        // Then
        assertEquals(1, result.hosts.size)
        val host = result.hosts.first()
        assertEquals("10.0.0.1", host.ipAddress)
        assertNull(host.hostname, "Hostname should be null when not present")
    }

    @Test
    fun `test parse XML with closed and filtered ports`() {
        // Given - XML with various port states
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="nmap" start="1234567890">
                <host>
                    <address addr="192.168.1.10" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                            <service name="http"/>
                        </port>
                        <port protocol="tcp" portid="8080">
                            <state state="filtered"/>
                        </port>
                        <port protocol="tcp" portid="3389">
                            <state state="closed"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        // When
        val result = nmapParserService.parseNmapXml(xmlContent)

        // Then
        val host = result.hosts.first()
        assertEquals(3, host.ports.size, "Should have 3 ports with different states")

        val port80 = host.ports.find { it.portNumber == 80 }
        assertEquals("open", port80?.state)

        val port8080 = host.ports.find { it.portNumber == 8080 }
        assertEquals("filtered", port8080?.state)

        val port3389 = host.ports.find { it.portNumber == 3389 }
        assertEquals("closed", port3389?.state)
    }

    @Test
    fun `test parse XML with UDP ports`() {
        // Given - XML with UDP ports
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="nmap" start="1234567890">
                <host>
                    <address addr="192.168.1.20" addrtype="ipv4"/>
                    <ports>
                        <port protocol="udp" portid="53">
                            <state state="open"/>
                            <service name="dns"/>
                        </port>
                        <port protocol="tcp" portid="53">
                            <state state="open"/>
                            <service name="dns"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        // When
        val result = nmapParserService.parseNmapXml(xmlContent)

        // Then
        val host = result.hosts.first()
        assertEquals(2, host.ports.size, "Should have both UDP and TCP ports")

        val udpPort = host.ports.find { it.protocol == "udp" }
        assertNotNull(udpPort)
        assertEquals(53, udpPort?.portNumber)

        val tcpPort = host.ports.find { it.protocol == "tcp" }
        assertNotNull(tcpPort)
        assertEquals(53, tcpPort?.portNumber)
    }

    @Test
    fun `test parse XML with service version information`() {
        // Given - XML with service version
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="nmap" start="1234567890">
                <host>
                    <address addr="192.168.1.30" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="22">
                            <state state="open"/>
                            <service name="ssh" product="OpenSSH" version="8.2p1" extrainfo="Ubuntu-4ubuntu0.5"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        // When
        val result = nmapParserService.parseNmapXml(xmlContent)

        // Then
        val host = result.hosts.first()
        val port22 = host.ports.first()

        assertEquals(22, port22.portNumber)
        assertEquals("ssh", port22.service)
        assertNotNull(port22.version)
        assertTrue(port22.version!!.contains("OpenSSH"), "Version should contain product name")
        assertTrue(port22.version!!.contains("8.2p1"), "Version should contain version number")
    }

    @Test
    fun `test parse XML without ports returns empty list`() {
        // Given - Host with no ports
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="nmap" start="1234567890">
                <host>
                    <address addr="192.168.1.40" addrtype="ipv4"/>
                    <hostnames>
                        <hostname name="test.local" type="PTR"/>
                    </hostnames>
                </host>
            </nmaprun>
        """.trimIndent()

        // When
        val result = nmapParserService.parseNmapXml(xmlContent)

        // Then
        assertEquals(1, result.hosts.size)
        val host = result.hosts.first()
        assertTrue(host.ports.isEmpty(), "Host should have no ports")
    }

    @Test
    fun `test parse empty nmap run returns empty hosts`() {
        // Given - Valid XML structure but no hosts
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="nmap" start="1234567890">
            </nmaprun>
        """.trimIndent()

        // When
        val result = nmapParserService.parseNmapXml(xmlContent)

        // Then
        assertTrue(result.hosts.isEmpty(), "Should return empty hosts list")
    }

    @Test
    fun `test malformed XML throws exception`() {
        // Given - Malformed XML
        val malformedXml = """
            <?xml version="1.0"?>
            <nmaprun>
                <host>
                    <address addr="192.168.1.1"
                </host>
            </nmaprun>
        """.trimIndent()

        // When/Then
        assertThrows<Exception> {
            nmapParserService.parseNmapXml(malformedXml)
        }
    }

    @Test
    fun `test empty XML throws exception`() {
        // Given
        val emptyXml = ""

        // When/Then
        assertThrows<Exception> {
            nmapParserService.parseNmapXml(emptyXml)
        }
    }

    @Test
    fun `test XXE attack prevention with DOCTYPE`() {
        // Given - XML with DOCTYPE (potential XXE attack)
        val xxeXml = """
            <?xml version="1.0"?>
            <!DOCTYPE nmaprun [
                <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <nmaprun scanner="nmap" start="1234567890">
                <host>
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                    <hostnames>
                        <hostname name="&xxe;" type="PTR"/>
                    </hostnames>
                </host>
            </nmaprun>
        """.trimIndent()

        // When - Should parse without error and without processing the entity
        val result = nmapParserService.parseNmapXml(xxeXml)

        // Then - Should have parsed successfully but hostname should be empty or entity not expanded
        assertNotNull(result)
        // XXE entity should NOT be expanded (would show file contents)
        if (result.hosts.isNotEmpty()) {
            val hostname = result.hosts.first().hostname
            // Hostname should be empty or the entity reference itself, NOT file contents
            assertFalse(hostname?.contains("root:") ?: false, "XXE entity should not be expanded")
        }
    }

    @Test
    fun `test parse scan date extraction`() {
        // Given
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="nmap" start="1234567890" startstr="Fri Feb 13 23:31:30 2009">
                <host>
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                </host>
            </nmaprun>
        """.trimIndent()

        // When
        val result = nmapParserService.parseNmapXml(xmlContent)

        // Then
        assertNotNull(result.scanDate)
        // scan date should be parsed from unix timestamp 1234567890
        assertEquals(1234567890L, result.scanDate.toEpochSecond())
    }

    @Test
    fun `test parse calculates scan duration`() {
        // Given
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="nmap" start="1234567890">
                <runstats>
                    <finished time="1234568000" elapsed="110"/>
                </runstats>
                <host>
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                </host>
            </nmaprun>
        """.trimIndent()

        // When
        val result = nmapParserService.parseNmapXml(xmlContent)

        // Then
        assertNotNull(result.duration)
        // Duration should be formatted (110 seconds = 1m 50s)
        assertTrue(result.duration.contains("1m") || result.duration.contains("110s"))
    }

    @Test
    fun `test parse handles missing required fields gracefully`() {
        // Given - Host without address (invalid but should not crash)
        val xmlContent = """
            <?xml version="1.0"?>
            <nmaprun scanner="nmap" start="1234567890">
                <host>
                    <hostnames>
                        <hostname name="test.local" type="PTR"/>
                    </hostnames>
                </host>
            </nmaprun>
        """.trimIndent()

        // When/Then - Should either skip the host or throw exception
        try {
            val result = nmapParserService.parseNmapXml(xmlContent)
            // If it doesn't throw, it should skip the invalid host
            assertTrue(result.hosts.isEmpty(), "Invalid host should be skipped")
        } catch (e: Exception) {
            // Throwing exception is also acceptable behavior
            assertNotNull(e)
        }
    }
}
