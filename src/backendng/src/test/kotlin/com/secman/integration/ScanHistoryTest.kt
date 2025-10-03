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
 * Integration test for multiple scans of same host over time
 *
 * Tests research.md Decision 4:
 * "Maintain point-in-time snapshots in separate ScanResult records"
 *
 * Scenario:
 * - Upload first scan with host 192.168.1.1 (ports 80, 443)
 * - Upload second scan with same host (ports 80, 22)
 * - Verify both ScanResult records exist for same asset
 * - Verify port history shows both scans
 * - Verify asset entity is reused (not duplicated)
 *
 * Expected to FAIL until ScanImportService implements history logic (TDD red phase).
 */
@MicronautTest(transactional = false)
class ScanHistoryTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var dataSource: DataSource

    /**
     * Integration Test: Multiple scans of same host create separate ScanResult records
     *
     * Verifies that scanning the same host multiple times creates:
     * - Multiple ScanResult records (one per scan)
     * - Single Asset entity (reused across scans)
     * - Complete port history preserved
     */
    @Test
    fun `should create separate scan results for same host scanned multiple times`() {
        // Arrange - First scan
        val firstScanXml = """
            <?xml version="1.0"?>
            <!DOCTYPE nmaprun PUBLIC "-//IDN nmap.org//DTD Nmap XML 1.04//EN" "https://nmap.org/data/nmap.dtd">
            <nmaprun scanner="nmap" start="1640000000" version="7.80">
                <host>
                    <status state="up"/>
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                    <hostnames>
                        <hostname name="test-server.example.com" type="PTR"/>
                    </hostnames>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                            <service name="http"/>
                        </port>
                        <port protocol="tcp" portid="443">
                            <state state="open"/>
                            <service name="https"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        val firstFile = File.createTempFile("first-scan", ".xml").apply {
            writeText(firstScanXml)
            deleteOnExit()
        }

        val token = authenticateAsAdmin()

        // Act - Upload first scan
        val firstBody = MultipartBody.builder()
            .addPart("file", firstFile.name, MediaType.APPLICATION_XML_TYPE, firstFile)
            .build()
        val firstRequest = HttpRequest.POST("/api/scan/upload-nmap", firstBody)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val firstResponse = client.toBlocking().exchange(firstRequest, Map::class.java)
        assertEquals(HttpStatus.OK, firstResponse.status)
        val firstScanId = (firstResponse.body()!!["scanId"] as Number).toLong()

        // Arrange - Second scan (same host, different ports)
        val secondScanXml = """
            <?xml version="1.0"?>
            <!DOCTYPE nmaprun PUBLIC "-//IDN nmap.org//DTD Nmap XML 1.04//EN" "https://nmap.org/data/nmap.dtd">
            <nmaprun scanner="nmap" start="1640100000" version="7.80">
                <host>
                    <status state="up"/>
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                    <hostnames>
                        <hostname name="test-server.example.com" type="PTR"/>
                    </hostnames>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                            <service name="http"/>
                        </port>
                        <port protocol="tcp" portid="22">
                            <state state="open"/>
                            <service name="ssh"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        val secondFile = File.createTempFile("second-scan", ".xml").apply {
            writeText(secondScanXml)
            deleteOnExit()
        }

        // Act - Upload second scan
        val secondBody = MultipartBody.builder()
            .addPart("file", secondFile.name, MediaType.APPLICATION_XML_TYPE, secondFile)
            .build()
        val secondRequest = HttpRequest.POST("/api/scan/upload-nmap", secondBody)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val secondResponse = client.toBlocking().exchange(secondRequest, Map::class.java)
        assertEquals(HttpStatus.OK, secondResponse.status)
        val secondScanId = (secondResponse.body()!!["scanId"] as Number).toLong()

        // Assert - Database verification
        dataSource.connection.use { conn ->
            // Verify 2 separate ScanResult records for same IP
            val scanResultQuery = """
                SELECT sr.id, sr.scan_id, sr.asset_id
                FROM scan_result sr
                WHERE sr.ip_address = '192.168.1.1'
                ORDER BY sr.scan_id
            """
            conn.prepareStatement(scanResultQuery).use { stmt ->
                val rs = stmt.executeQuery()

                assertTrue(rs.next(), "Should have first ScanResult")
                val firstAssetId = rs.getLong("asset_id")
                assertEquals(firstScanId, rs.getLong("scan_id"), "First ScanResult should link to first scan")

                assertTrue(rs.next(), "Should have second ScanResult")
                val secondAssetId = rs.getLong("asset_id")
                assertEquals(secondScanId, rs.getLong("scan_id"), "Second ScanResult should link to second scan")

                // Critical: Both scan results should reference the SAME asset
                assertEquals(firstAssetId, secondAssetId, "Both ScanResults should reference same Asset (asset reuse)")

                assertFalse(rs.next(), "Should have exactly 2 ScanResult records")
            }

            // Verify only ONE Asset entity exists for this IP
            val assetQuery = "SELECT COUNT(*) FROM asset WHERE ip_address = '192.168.1.1'"
            conn.prepareStatement(assetQuery).use { stmt ->
                val rs = stmt.executeQuery()
                rs.next()
                assertEquals(1, rs.getInt(1), "Should have exactly 1 Asset for IP 192.168.1.1 (not duplicated)")
            }

            // Verify port history preserved for first scan (2 ports: 80, 443)
            val firstPortsQuery = """
                SELECT port_number FROM scan_port sp
                JOIN scan_result sr ON sp.scan_result_id = sr.id
                WHERE sr.scan_id = ?
                ORDER BY port_number
            """
            conn.prepareStatement(firstPortsQuery).use { stmt ->
                stmt.setLong(1, firstScanId)
                val rs = stmt.executeQuery()

                assertTrue(rs.next())
                assertEquals(80, rs.getInt("port_number"))
                assertTrue(rs.next())
                assertEquals(443, rs.getInt("port_number"))
                assertFalse(rs.next())
            }

            // Verify port history preserved for second scan (2 ports: 22, 80)
            val secondPortsQuery = """
                SELECT port_number FROM scan_port sp
                JOIN scan_result sr ON sp.scan_result_id = sr.id
                WHERE sr.scan_id = ?
                ORDER BY port_number
            """
            conn.prepareStatement(secondPortsQuery).use { stmt ->
                stmt.setLong(1, secondScanId)
                val rs = stmt.executeQuery()

                assertTrue(rs.next())
                assertEquals(22, rs.getInt("port_number"))
                assertTrue(rs.next())
                assertEquals(80, rs.getInt("port_number"))
                assertFalse(rs.next())
            }
        }
    }

    /**
     * Integration Test: Port history API shows all scans for same host
     *
     * Verifies that GET /api/assets/{id}/ports returns all scan results chronologically
     */
    @Test
    fun `should return complete port history across multiple scans`() {
        // Arrange - Upload two scans of same host
        val scan1Xml = """
            <?xml version="1.0"?>
            <!DOCTYPE nmaprun PUBLIC "-//IDN nmap.org//DTD Nmap XML 1.04//EN" "https://nmap.org/data/nmap.dtd">
            <nmaprun scanner="nmap" start="1640000000" version="7.80">
                <host>
                    <status state="up"/>
                    <address addr="10.10.10.10" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="8080">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        val scan2Xml = """
            <?xml version="1.0"?>
            <!DOCTYPE nmaprun PUBLIC "-//IDN nmap.org//DTD Nmap XML 1.04//EN" "https://nmap.org/data/nmap.dtd">
            <nmaprun scanner="nmap" start="1640100000" version="7.80">
                <host>
                    <status state="up"/>
                    <address addr="10.10.10.10" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="8080">
                            <state state="closed"/>
                        </port>
                        <port protocol="tcp" portid="3000">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        val file1 = File.createTempFile("history-scan1", ".xml").apply {
            writeText(scan1Xml)
            deleteOnExit()
        }
        val file2 = File.createTempFile("history-scan2", ".xml").apply {
            writeText(scan2Xml)
            deleteOnExit()
        }

        val token = authenticateAsAdmin()

        // Upload both scans
        listOf(file1, file2).forEach { file ->
            val body = MultipartBody.builder()
                .addPart("file", file.name, MediaType.APPLICATION_XML_TYPE, file)
                .build()
            val request = HttpRequest.POST("/api/scan/upload-nmap", body)
                .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
                .bearerAuth(token)
            client.toBlocking().exchange(request, Map::class.java)
        }

        // Get asset ID
        val assetId = dataSource.connection.use { conn ->
            val query = "SELECT id FROM asset WHERE ip_address = '10.10.10.10'"
            conn.prepareStatement(query).use { stmt ->
                val rs = stmt.executeQuery()
                rs.next()
                rs.getLong("id")
            }
        }

        // Act - Get port history
        val historyRequest = HttpRequest.GET<Any>("/api/assets/$assetId/ports")
            .bearerAuth(token)
        val historyResponse = client.toBlocking().exchange(historyRequest, Map::class.java)

        // Assert
        assertEquals(HttpStatus.OK, historyResponse.status)
        val portHistory = historyResponse.body()!!

        @Suppress("UNCHECKED_CAST")
        val scans = portHistory["scans"] as List<Map<String, Any>>
        assertEquals(2, scans.size, "Should have 2 scan entries in port history")

        // Verify scans are ordered by date (newest first per contract)
        // Second scan should be first (higher timestamp)
        val firstScan = scans[0]
        @Suppress("UNCHECKED_CAST")
        val firstScanPorts = firstScan["ports"] as List<Map<String, Any>>
        assertEquals(2, firstScanPorts.size, "Most recent scan should have 2 ports")

        val secondScan = scans[1]
        @Suppress("UNCHECKED_CAST")
        val secondScanPorts = secondScan["ports"] as List<Map<String, Any>>
        assertEquals(1, secondScanPorts.size, "Older scan should have 1 port")
    }

    /**
     * Integration Test: Asset metadata updated on rescan
     *
     * Verifies that rescanning a host updates asset timestamp but reuses entity
     */
    @Test
    fun `should update asset last_seen timestamp on rescan`() {
        // Arrange - First scan
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE nmaprun PUBLIC "-//IDN nmap.org//DTD Nmap XML 1.04//EN" "https://nmap.org/data/nmap.dtd">
            <nmaprun scanner="nmap" start="1640000000" version="7.80">
                <host>
                    <status state="up"/>
                    <address addr="172.20.0.1" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent()

        val file = File.createTempFile("rescan-test", ".xml").apply {
            writeText(xml)
            deleteOnExit()
        }

        val token = authenticateAsAdmin()
        val body = MultipartBody.builder()
            .addPart("file", file.name, MediaType.APPLICATION_XML_TYPE, file)
            .build()

        // Act - Upload first scan
        val request1 = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)
        client.toBlocking().exchange(request1, Map::class.java)

        // Get initial timestamp
        val firstTimestamp = dataSource.connection.use { conn ->
            val query = "SELECT last_seen FROM asset WHERE ip_address = '172.20.0.1'"
            conn.prepareStatement(query).use { stmt ->
                val rs = stmt.executeQuery()
                rs.next()
                rs.getTimestamp("last_seen")
            }
        }

        Thread.sleep(1000) // Ensure timestamp difference

        // Act - Upload second scan (rescan)
        val request2 = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)
        client.toBlocking().exchange(request2, Map::class.java)

        // Assert - Verify timestamp updated
        val secondTimestamp = dataSource.connection.use { conn ->
            val query = "SELECT last_seen FROM asset WHERE ip_address = '172.20.0.1'"
            conn.prepareStatement(query).use { stmt ->
                val rs = stmt.executeQuery()
                rs.next()
                rs.getTimestamp("last_seen")
            }
        }

        assertTrue(secondTimestamp.after(firstTimestamp), "last_seen should be updated on rescan")
    }

    // Helper methods
    private fun authenticateAsAdmin(): String {
        val credentials = UsernamePasswordCredentials("admin", "admin")
        val request = HttpRequest.POST("/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()!!["access_token"] as String
    }
}
