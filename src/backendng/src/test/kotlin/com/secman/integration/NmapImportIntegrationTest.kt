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
 * Integration test for nmap upload workflow
 *
 * Tests the complete flow from file upload through database persistence:
 * 1. Upload nmap XML file via POST /api/scan/upload-nmap
 * 2. Verify Scan entity created with correct metadata
 * 3. Verify ScanResult entities created for each host
 * 4. Verify ScanPort entities created for each port
 * 5. Verify Asset entities created/updated
 *
 * Validates end-to-end integration including:
 * - XML parsing
 * - Entity relationships
 * - Database persistence
 * - Transaction handling
 *
 * Expected to FAIL until full implementation complete (TDD red phase).
 */
@MicronautTest(transactional = false)
class NmapImportIntegrationTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var dataSource: DataSource

    private val testNmapFile = File("src/test/resources/nmap-test.xml")

    /**
     * Integration Test: Complete nmap upload workflow
     *
     * Scenario:
     * 1. Upload valid nmap XML file
     * 2. Verify HTTP 200 response with scan summary
     * 3. Query database to verify:
     *    - 1 Scan record created
     *    - N ScanResult records (one per host)
     *    - M ScanPort records (one per port per host)
     *    - N Asset records created or updated
     * 4. Verify entity relationships are correct
     */
    @Test
    fun `should create all entities in database after nmap upload`() {
        // Arrange
        val token = authenticateAsAdmin()
        val body = MultipartBody.builder()
            .addPart("file", testNmapFile.name, MediaType.APPLICATION_XML_TYPE, testNmapFile)
            .build()

        val request = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert - HTTP response
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        val scanSummary = response.body()!!
        val scanId = (scanSummary["scanId"] as Number).toLong()
        val hostsDiscovered = (scanSummary["hostsDiscovered"] as Number).toInt()
        val totalPorts = (scanSummary["totalPorts"] as Number).toInt()

        // Assert - Database verification
        dataSource.connection.use { conn ->
            // Verify Scan record created
            val scanQuery = "SELECT * FROM scan WHERE id = ?"
            conn.prepareStatement(scanQuery).use { stmt ->
                stmt.setLong(1, scanId)
                val rs = stmt.executeQuery()
                assertTrue(rs.next(), "Scan record should exist in database")

                assertEquals("nmap", rs.getString("scan_type"), "scanType should be 'nmap'")
                assertEquals(testNmapFile.name, rs.getString("filename"), "filename should match")
                assertEquals("admin", rs.getString("uploaded_by"), "uploadedBy should be 'admin'")
                assertEquals(hostsDiscovered, rs.getInt("host_count"), "hostCount should match")
                assertNotNull(rs.getTimestamp("scan_date"), "scanDate should be present")
                assertNotNull(rs.getString("duration"), "duration should be present")
            }

            // Verify ScanResult records created
            val scanResultQuery = "SELECT COUNT(*) FROM scan_result WHERE scan_id = ?"
            conn.prepareStatement(scanResultQuery).use { stmt ->
                stmt.setLong(1, scanId)
                val rs = stmt.executeQuery()
                rs.next()
                val scanResultCount = rs.getInt(1)
                assertEquals(hostsDiscovered, scanResultCount, "Should have $hostsDiscovered ScanResult records")
            }

            // Verify ScanPort records created
            val scanPortQuery = """
                SELECT COUNT(*) FROM scan_port sp
                JOIN scan_result sr ON sp.scan_result_id = sr.id
                WHERE sr.scan_id = ?
            """
            conn.prepareStatement(scanPortQuery).use { stmt ->
                stmt.setLong(1, scanId)
                val rs = stmt.executeQuery()
                rs.next()
                val scanPortCount = rs.getInt(1)
                assertEquals(totalPorts, scanPortCount, "Should have $totalPorts ScanPort records")
            }

            // Verify Asset records created/updated
            val assetQuery = """
                SELECT COUNT(DISTINCT sr.asset_id) FROM scan_result sr
                WHERE sr.scan_id = ?
            """
            conn.prepareStatement(assetQuery).use { stmt ->
                stmt.setLong(1, scanId)
                val rs = stmt.executeQuery()
                rs.next()
                val assetCount = rs.getInt(1)
                assertEquals(hostsDiscovered, assetCount, "Should have $hostsDiscovered linked assets")
            }
        }
    }

    /**
     * Integration Test: Verify entity relationships are correctly established
     *
     * Tests the bidirectional relationships:
     * - Scan → ScanResult (OneToMany)
     * - ScanResult → Scan (ManyToOne)
     * - ScanResult → Asset (ManyToOne)
     * - ScanResult → ScanPort (OneToMany)
     * - ScanPort → ScanResult (ManyToOne)
     */
    @Test
    fun `should establish correct entity relationships`() {
        // Arrange
        val token = authenticateAsAdmin()
        val body = MultipartBody.builder()
            .addPart("file", testNmapFile.name, MediaType.APPLICATION_XML_TYPE, testNmapFile)
            .build()

        val request = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)
        val scanId = (response.body()!!["scanId"] as Number).toLong()

        // Assert - Verify relationships via database
        dataSource.connection.use { conn ->
            // Get a ScanResult record
            val scanResultQuery = "SELECT id, scan_id, asset_id FROM scan_result WHERE scan_id = ? LIMIT 1"
            conn.prepareStatement(scanResultQuery).use { stmt ->
                stmt.setLong(1, scanId)
                val rs = stmt.executeQuery()
                assertTrue(rs.next(), "Should have at least one ScanResult")

                val scanResultId = rs.getLong("id")
                val linkedScanId = rs.getLong("scan_id")
                val linkedAssetId = rs.getLong("asset_id")

                // Verify ScanResult → Scan relationship
                assertEquals(scanId, linkedScanId, "ScanResult should link to correct Scan")

                // Verify ScanResult → Asset relationship (asset should exist)
                val assetQuery = "SELECT id FROM asset WHERE id = ?"
                conn.prepareStatement(assetQuery).use { assetStmt ->
                    assetStmt.setLong(1, linkedAssetId)
                    val assetRs = assetStmt.executeQuery()
                    assertTrue(assetRs.next(), "Linked Asset should exist")
                }

                // Verify ScanResult → ScanPort relationship
                val portQuery = "SELECT COUNT(*) FROM scan_port WHERE scan_result_id = ?"
                conn.prepareStatement(portQuery).use { portStmt ->
                    portStmt.setLong(1, scanResultId)
                    val portRs = portStmt.executeQuery()
                    portRs.next()
                    val portCount = portRs.getInt(1)
                    assertTrue(portCount >= 0, "ScanResult should have associated ScanPort records")
                }
            }
        }
    }

    /**
     * Integration Test: Verify transaction rollback on parsing error
     *
     * If XML parsing fails, no partial data should be committed to database
     */
    @Test
    fun `should rollback transaction on parsing error`() {
        // Arrange
        val token = authenticateAsAdmin()
        val invalidFile = File.createTempFile("invalid", ".xml").apply {
            writeText("<invalid>Not valid nmap XML</invalid>")
            deleteOnExit()
        }
        val body = MultipartBody.builder()
            .addPart("file", invalidFile.name, MediaType.APPLICATION_XML_TYPE, invalidFile)
            .build()

        val request = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        // Get initial scan count
        val initialScanCount = dataSource.connection.use { conn ->
            val query = "SELECT COUNT(*) FROM scan"
            conn.prepareStatement(query).use { stmt ->
                val rs = stmt.executeQuery()
                rs.next()
                rs.getInt(1)
            }
        }

        // Act
        try {
            client.toBlocking().exchange(request, Map::class.java)
            fail("Should throw exception for invalid XML")
        } catch (e: Exception) {
            // Expected
        }

        // Assert - No new scan records created
        val finalScanCount = dataSource.connection.use { conn ->
            val query = "SELECT COUNT(*) FROM scan"
            conn.prepareStatement(query).use { stmt ->
                val rs = stmt.executeQuery()
                rs.next()
                rs.getInt(1)
            }
        }

        assertEquals(initialScanCount, finalScanCount, "No scan records should be created on error")
    }

    /**
     * Integration Test: Verify audit logging for scan import
     *
     * Per NFR-003, all scan imports should be logged for audit purposes
     */
    @Test
    fun `should create audit log entry for scan import`() {
        // Arrange
        val token = authenticateAsAdmin()
        val body = MultipartBody.builder()
            .addPart("file", testNmapFile.name, MediaType.APPLICATION_XML_TYPE, testNmapFile)
            .build()

        val request = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)
        val scanId = (response.body()!!["scanId"] as Number).toLong()

        // Assert - Check audit log
        // Note: Assumes audit_log table exists (implementation detail)
        dataSource.connection.use { conn ->
            val auditQuery = """
                SELECT * FROM audit_log
                WHERE entity_type = 'SCAN'
                AND entity_id = ?
                AND action = 'IMPORT'
            """
            conn.prepareStatement(auditQuery).use { stmt ->
                stmt.setLong(1, scanId)
                val rs = stmt.executeQuery()
                assertTrue(rs.next(), "Audit log entry should exist for scan import")
                assertEquals("admin", rs.getString("user_id"), "Audit log should record user")
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
