package com.secman.integration

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.security.authentication.UsernamePasswordCredentials
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import javax.sql.DataSource

/**
 * Integration test for duplicate IP handling
 *
 * Tests research.md Decision 2:
 * "Skip duplicates with warning log, keeping first occurrence"
 *
 * Scenario:
 * - Create nmap XML file with duplicate IP addresses
 * - Upload file
 * - Verify only first occurrence is imported
 * - Verify warning is logged for duplicate
 *
 * Expected to FAIL until ScanImportService implements duplicate detection (TDD red phase).
 */
@MicronautTest(transactional = false)
class DuplicateHostTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var dataSource: DataSource

    /**
     * Integration Test: Duplicate IP in same scan should be skipped
     *
     * Creates a test nmap file with duplicate IPs and verifies:
     * 1. Only first occurrence is imported
     * 2. Host count reflects actual unique hosts
     * 3. Warning log entry exists for duplicate
     */
    @Test
    fun `should skip duplicate IP addresses in same scan`() {
        // Arrange - Create nmap XML with duplicate IPs
        val duplicateNmapXml = """
            <?xml version="1.0"?>
            <!DOCTYPE nmaprun PUBLIC "-//IDN nmap.org//DTD Nmap XML 1.04//EN" "https://nmap.org/data/nmap.dtd">
            <nmaprun scanner="nmap" start="1234567890" version="7.80">
                <host>
                    <status state="up"/>
                    <address addr="192.168.1.100" addrtype="ipv4"/>
                    <hostnames>
                        <hostname name="server1.example.com" type="PTR"/>
                    </hostnames>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                            <service name="http"/>
                        </port>
                    </ports>
                </host>
                <host>
                    <status state="up"/>
                    <address addr="192.168.1.100" addrtype="ipv4"/>
                    <hostnames>
                        <hostname name="server1-duplicate.example.com" type="PTR"/>
                    </hostnames>
                    <ports>
                        <port protocol="tcp" portid="443">
                            <state state="open"/>
                            <service name="https"/>
                        </port>
                    </ports>
                </host>
                <host>
                    <status state="up"/>
                    <address addr="192.168.1.101" addrtype="ipv4"/>
                    <hostnames>
                        <hostname name="server2.example.com" type="PTR"/>
                    </hostnames>
                    <ports>
                        <port protocol="tcp" portid="22">
                            <state state="open"/>
                            <service name="ssh"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        val testFile = File.createTempFile("duplicate-test", ".xml").apply {
            writeText(duplicateNmapXml)
            deleteOnExit()
        }

        val token = authenticateAsAdmin()
        val body = MultipartBody.builder()
            .addPart("file", testFile.name, MediaType.APPLICATION_XML_TYPE, testFile)
            .build()

        val request = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert - Response validation
        assertEquals(HttpStatus.OK, response.status)
        val scanSummary = response.body()!!
        val scanId = (scanSummary["scanId"] as Number).toLong()
        val hostsDiscovered = (scanSummary["hostsDiscovered"] as Number).toInt()

        // Should report 2 unique hosts (192.168.1.100 once, 192.168.1.101 once)
        assertEquals(2, hostsDiscovered, "Should skip duplicate and report 2 unique hosts")

        // Assert - Database verification
        dataSource.connection.use { conn ->
            // Verify only 2 ScanResult records created
            val scanResultQuery = "SELECT ip_address FROM scan_result WHERE scan_id = ? ORDER BY ip_address"
            conn.prepareStatement(scanResultQuery).use { stmt ->
                stmt.setLong(1, scanId)
                val rs = stmt.executeQuery()

                val ipAddresses = mutableListOf<String>()
                while (rs.next()) {
                    ipAddresses.add(rs.getString("ip_address"))
                }

                assertEquals(2, ipAddresses.size, "Should have exactly 2 ScanResult records")
                assertEquals("192.168.1.100", ipAddresses[0], "First IP should be present")
                assertEquals("192.168.1.101", ipAddresses[1], "Second IP should be present")
            }

            // Verify first occurrence data is kept (hostname "server1.example.com")
            val hostnameQuery = "SELECT hostname FROM scan_result WHERE scan_id = ? AND ip_address = ?"
            conn.prepareStatement(hostnameQuery).use { stmt ->
                stmt.setLong(1, scanId)
                stmt.setString(2, "192.168.1.100")
                val rs = stmt.executeQuery()
                assertTrue(rs.next(), "Should have record for first occurrence")
                assertEquals("server1.example.com", rs.getString("hostname"),
                    "Should keep hostname from first occurrence, not duplicate")
            }
        }
    }

    /**
     * Integration Test: Warning log entry for duplicate IP
     *
     * Verifies that duplicate IP detection generates appropriate log entry
     */
    @Test
    fun `should log warning for duplicate IP address`() {
        // Arrange - Create nmap XML with duplicate IPs
        val duplicateNmapXml = """
            <?xml version="1.0"?>
            <!DOCTYPE nmaprun PUBLIC "-//IDN nmap.org//DTD Nmap XML 1.04//EN" "https://nmap.org/data/nmap.dtd">
            <nmaprun scanner="nmap" start="1234567890" version="7.80">
                <host>
                    <status state="up"/>
                    <address addr="10.0.0.1" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
                <host>
                    <status state="up"/>
                    <address addr="10.0.0.1" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="443">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        val testFile = File.createTempFile("duplicate-log-test", ".xml").apply {
            writeText(duplicateNmapXml)
            deleteOnExit()
        }

        val token = authenticateAsAdmin()
        val body = MultipartBody.builder()
            .addPart("file", testFile.name, MediaType.APPLICATION_XML_TYPE, testFile)
            .build()

        val request = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)
        val scanId = (response.body()!!["scanId"] as Number).toLong()

        // Assert - Check for warning log entry
        dataSource.connection.use { conn ->
            val logQuery = """
                SELECT * FROM audit_log
                WHERE entity_type = 'SCAN'
                AND entity_id = ?
                AND level = 'WARN'
                AND message LIKE '%duplicate%10.0.0.1%'
            """
            conn.prepareStatement(logQuery).use { stmt ->
                stmt.setLong(1, scanId)
                val rs = stmt.executeQuery()
                assertTrue(rs.next(), "Should have warning log entry for duplicate IP 10.0.0.1")
            }
        }
    }

    /**
     * Integration Test: No duplicates in clean scan
     *
     * Verifies that scans without duplicates process normally without warnings
     */
    @Test
    fun `should not log warnings when no duplicates exist`() {
        // Arrange - Create nmap XML without duplicates
        val cleanNmapXml = """
            <?xml version="1.0"?>
            <!DOCTYPE nmaprun PUBLIC "-//IDN nmap.org//DTD Nmap XML 1.04//EN" "https://nmap.org/data/nmap.dtd">
            <nmaprun scanner="nmap" start="1234567890" version="7.80">
                <host>
                    <status state="up"/>
                    <address addr="192.168.1.10" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
                <host>
                    <status state="up"/>
                    <address addr="192.168.1.11" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="443">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        val testFile = File.createTempFile("clean-test", ".xml").apply {
            writeText(cleanNmapXml)
            deleteOnExit()
        }

        val token = authenticateAsAdmin()
        val body = MultipartBody.builder()
            .addPart("file", testFile.name, MediaType.APPLICATION_XML_TYPE, testFile)
            .build()

        val request = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)
        val scanId = (response.body()!!["scanId"] as Number).toLong()
        val hostsDiscovered = (response.body()!!["hostsDiscovered"] as Number).toInt()

        // Assert - Should process both hosts
        assertEquals(2, hostsDiscovered, "Should import both unique hosts")

        // Assert - No duplicate warnings
        dataSource.connection.use { conn ->
            val logQuery = """
                SELECT COUNT(*) FROM audit_log
                WHERE entity_type = 'SCAN'
                AND entity_id = ?
                AND level = 'WARN'
                AND message LIKE '%duplicate%'
            """
            conn.prepareStatement(logQuery).use { stmt ->
                stmt.setLong(1, scanId)
                val rs = stmt.executeQuery()
                rs.next()
                assertEquals(0, rs.getInt(1), "Should have no duplicate warnings for clean scan")
            }
        }
    }

    // Helper methods
    private fun authenticateAsAdmin(): String {
        val credentials = UsernamePasswordCredentials("admin", "admin")
        val request = HttpRequest.POST("/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()!!["access_token"] as String
    }
}
