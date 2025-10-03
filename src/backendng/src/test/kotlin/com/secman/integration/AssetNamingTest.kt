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
 * Integration test for asset naming fallback
 *
 * Tests research.md Decision 1:
 * "Use IP address as asset name when hostname is missing"
 *
 * Scenario:
 * - Create nmap XML with hosts having no hostname
 * - Upload file
 * - Verify assets created with IP as name
 * - Verify assets with hostnames use hostname as name
 *
 * Expected to FAIL until ScanImportService implements naming logic (TDD red phase).
 */
@MicronautTest(transactional = false)
class AssetNamingTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var dataSource: DataSource

    /**
     * Integration Test: Asset naming when hostname is missing
     *
     * Verifies that assets created from hosts without hostnames use IP address as name
     */
    @Test
    fun `should use IP address as asset name when hostname is missing`() {
        // Arrange - Create nmap XML with host without hostname
        val noHostnameXml = """
            <?xml version="1.0"?>
            <!DOCTYPE nmaprun PUBLIC "-//IDN nmap.org//DTD Nmap XML 1.04//EN" "https://nmap.org/data/nmap.dtd">
            <nmaprun scanner="nmap" start="1234567890" version="7.80">
                <host>
                    <status state="up"/>
                    <address addr="192.168.1.50" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                            <service name="http"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        val testFile = File.createTempFile("no-hostname-test", ".xml").apply {
            writeText(noHostnameXml)
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

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val scanSummary = response.body()!!
        val scanId = (scanSummary["scanId"] as Number).toLong()

        // Verify asset was created with IP as name
        dataSource.connection.use { conn ->
            val assetQuery = """
                SELECT a.name, a.ip_address
                FROM asset a
                JOIN scan_result sr ON sr.asset_id = a.id
                WHERE sr.scan_id = ?
            """
            conn.prepareStatement(assetQuery).use { stmt ->
                stmt.setLong(1, scanId)
                val rs = stmt.executeQuery()
                assertTrue(rs.next(), "Should have created asset")

                val assetName = rs.getString("name")
                val ipAddress = rs.getString("ip_address")

                assertEquals("192.168.1.50", ipAddress, "IP address should match")
                assertEquals("192.168.1.50", assetName, "Asset name should be IP address when hostname missing")
            }
        }
    }

    /**
     * Integration Test: Asset naming when hostname is present
     *
     * Verifies that assets created from hosts with hostnames use hostname as name
     */
    @Test
    fun `should use hostname as asset name when hostname is present`() {
        // Arrange - Create nmap XML with host with hostname
        val withHostnameXml = """
            <?xml version="1.0"?>
            <!DOCTYPE nmaprun PUBLIC "-//IDN nmap.org//DTD Nmap XML 1.04//EN" "https://nmap.org/data/nmap.dtd">
            <nmaprun scanner="nmap" start="1234567890" version="7.80">
                <host>
                    <status state="up"/>
                    <address addr="10.0.0.100" addrtype="ipv4"/>
                    <hostnames>
                        <hostname name="webserver.example.com" type="PTR"/>
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

        val testFile = File.createTempFile("with-hostname-test", ".xml").apply {
            writeText(withHostnameXml)
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

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val scanSummary = response.body()!!
        val scanId = (scanSummary["scanId"] as Number).toLong()

        // Verify asset was created with hostname as name
        dataSource.connection.use { conn ->
            val assetQuery = """
                SELECT a.name, a.ip_address
                FROM asset a
                JOIN scan_result sr ON sr.asset_id = a.id
                WHERE sr.scan_id = ?
            """
            conn.prepareStatement(assetQuery).use { stmt ->
                stmt.setLong(1, scanId)
                val rs = stmt.executeQuery()
                assertTrue(rs.next(), "Should have created asset")

                val assetName = rs.getString("name")
                val ipAddress = rs.getString("ip_address")

                assertEquals("10.0.0.100", ipAddress, "IP address should match")
                assertEquals("webserver.example.com", assetName, "Asset name should be hostname when present")
            }
        }
    }

    /**
     * Integration Test: Mixed scenario with both hostname and no hostname
     *
     * Verifies naming logic works correctly when scan contains both types of hosts
     */
    @Test
    fun `should handle mixed scenario with and without hostnames`() {
        // Arrange - Create nmap XML with mixed hosts
        val mixedXml = """
            <?xml version="1.0"?>
            <!DOCTYPE nmaprun PUBLIC "-//IDN nmap.org//DTD Nmap XML 1.04//EN" "https://nmap.org/data/nmap.dtd">
            <nmaprun scanner="nmap" start="1234567890" version="7.80">
                <host>
                    <status state="up"/>
                    <address addr="172.16.0.1" addrtype="ipv4"/>
                    <hostnames>
                        <hostname name="gateway.local" type="PTR"/>
                    </hostnames>
                    <ports>
                        <port protocol="tcp" portid="22">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
                <host>
                    <status state="up"/>
                    <address addr="172.16.0.2" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
                <host>
                    <status state="up"/>
                    <address addr="172.16.0.3" addrtype="ipv4"/>
                    <hostnames>
                        <hostname name="database.local" type="PTR"/>
                    </hostnames>
                    <ports>
                        <port protocol="tcp" portid="3306">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        val testFile = File.createTempFile("mixed-test", ".xml").apply {
            writeText(mixedXml)
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

        // Assert - Verify each asset has correct name
        dataSource.connection.use { conn ->
            val assetQuery = """
                SELECT a.name, a.ip_address, sr.hostname
                FROM asset a
                JOIN scan_result sr ON sr.asset_id = a.id
                WHERE sr.scan_id = ?
                ORDER BY a.ip_address
            """
            conn.prepareStatement(assetQuery).use { stmt ->
                stmt.setLong(1, scanId)
                val rs = stmt.executeQuery()

                // First host: 172.16.0.1 with hostname "gateway.local"
                assertTrue(rs.next(), "Should have first asset")
                assertEquals("172.16.0.1", rs.getString("ip_address"))
                assertEquals("gateway.local", rs.getString("name"))
                assertEquals("gateway.local", rs.getString("hostname"))

                // Second host: 172.16.0.2 without hostname (should use IP)
                assertTrue(rs.next(), "Should have second asset")
                assertEquals("172.16.0.2", rs.getString("ip_address"))
                assertEquals("172.16.0.2", rs.getString("name"))
                assertNull(rs.getString("hostname"))

                // Third host: 172.16.0.3 with hostname "database.local"
                assertTrue(rs.next(), "Should have third asset")
                assertEquals("172.16.0.3", rs.getString("ip_address"))
                assertEquals("database.local", rs.getString("name"))
                assertEquals("database.local", rs.getString("hostname"))

                assertFalse(rs.next(), "Should have exactly 3 assets")
            }
        }
    }

    /**
     * Integration Test: Empty hostname element should be treated as missing
     *
     * Verifies that empty <hostnames></hostnames> is treated same as missing hostname
     */
    @Test
    fun `should treat empty hostname element as missing hostname`() {
        // Arrange - Create nmap XML with empty hostnames element
        val emptyHostnameXml = """
            <?xml version="1.0"?>
            <!DOCTYPE nmaprun PUBLIC "-//IDN nmap.org//DTD Nmap XML 1.04//EN" "https://nmap.org/data/nmap.dtd">
            <nmaprun scanner="nmap" start="1234567890" version="7.80">
                <host>
                    <status state="up"/>
                    <address addr="192.168.100.1" addrtype="ipv4"/>
                    <hostnames></hostnames>
                    <ports>
                        <port protocol="tcp" portid="22">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        val testFile = File.createTempFile("empty-hostname-test", ".xml").apply {
            writeText(emptyHostnameXml)
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

        // Assert - Should use IP as name
        dataSource.connection.use { conn ->
            val assetQuery = """
                SELECT a.name FROM asset a
                JOIN scan_result sr ON sr.asset_id = a.id
                WHERE sr.scan_id = ?
            """
            conn.prepareStatement(assetQuery).use { stmt ->
                stmt.setLong(1, scanId)
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals("192.168.100.1", rs.getString("name"),
                    "Should use IP as name when hostname element is empty")
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
