package com.secman.integration

import com.secman.repository.AssetRepository
import com.secman.repository.ScanResultRepository
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
 * Integration test for Masscan XML import workflow
 *
 * Related to: Feature 005-add-funtionality-to (Masscan XML Import)
 *
 * Tests the complete flow from file upload through database persistence:
 * 1. Upload Masscan XML file via POST /api/import/upload-masscan-xml
 * 2. Verify Asset entities created/updated with correct defaults
 * 3. Verify ScanResult entities created for each open port
 * 4. Verify timestamps preserved from XML
 * 5. Verify historical tracking (duplicates kept as separate records)
 *
 * Validates end-to-end integration including:
 * - XML parsing (MasscanParserService)
 * - Asset find-or-create logic
 * - Default value application
 * - Port state filtering (only "open")
 * - Database persistence
 * - Transaction handling
 *
 * Expected to FAIL until full implementation complete (TDD red phase).
 */
@MicronautTest(transactional = false)
class MasscanImportIntegrationTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var scanResultRepository: ScanResultRepository

    private val testMasscanFile = File("../../testdata/masscan.xml")

    /**
     * Integration Test 1: Import new assets from Masscan XML
     *
     * Scenario:
     * 1. Upload testdata/masscan.xml (contains IP 193.99.144.85 with ports 80, 443)
     * 2. Verify HTTP 200 response with correct counts
     * 3. Query database to verify:
     *    - Asset created with IP 193.99.144.85
     *    - Default values applied: owner="Security Team", type="Scanned Host", name=null, description=""
     *    - 2 ScanResult records (ports 80 and 443)
     *    - Timestamps preserved from XML (endtime: 1759560572)
     */
    @Test
    fun `testImportNewAssets - Upload masscan xml verify assets created`() {
        // Arrange
        val token = authenticateAsUser()

        // Clean up any existing test data
        val existingAsset = assetRepository.findByIp("193.99.144.85").firstOrNull()
        existingAsset?.let { assetRepository.delete(it) }

        val body = MultipartBody.builder()
            .addPart("xmlFile", testMasscanFile.name, MediaType.APPLICATION_XML_TYPE, testMasscanFile)
            .build()

        val request = HttpRequest.POST("/api/import/upload-masscan-xml", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert - HTTP response
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        val importResponse = response.body()!!
        assertEquals(1, (importResponse["assetsCreated"] as Number).toInt(), "Should create 1 new asset")
        assertEquals(2, (importResponse["portsImported"] as Number).toInt(), "Should import 2 ports")

        // Assert - Database state
        val asset = assetRepository.findByIp("193.99.144.85").firstOrNull()
            ?: throw AssertionError("Asset with IP 193.99.144.85 should exist")

        // Verify default values applied
        assertEquals("193.99.144.85", asset.ip)
        assertEquals("193.99.144.85", asset.name, "Name should be IP (Masscan doesn't provide hostname)")
        assertEquals("Scanned Host", asset.type, "Type should be 'Scanned Host'")
        assertEquals("Security Team", asset.owner, "Owner should be 'Security Team'")
        assertEquals("", asset.description, "Description should be empty string")

        // Verify timestamp preserved (epoch 1759560572 = 2025-10-04 08:49:32)
        assertNotNull(asset.lastSeen)
        assertEquals(2025, asset.lastSeen!!.year)
        assertEquals(10, asset.lastSeen!!.monthValue)
        assertEquals(4, asset.lastSeen!!.dayOfMonth)

        // Verify scan results created (one ScanResult per host)
        val scanResults = scanResultRepository.findByAssetIdOrderByDiscoveredAtDesc(asset.id!!)
        assertEquals(1, scanResults.size, "Should have 1 scan result (1 host)")

        val scanResult = scanResults.first()
        assertEquals("193.99.144.85", scanResult.ipAddress)
        assertNull(scanResult.hostname, "Hostname should be null (Masscan doesn't provide)")

        // Verify ports within the scan result
        val ports = scanResult.ports
        assertEquals(2, ports.size, "Should have 2 ports")

        val portNumbers = ports.map { it.portNumber }
        assertTrue(portNumbers.contains(80), "Should have port 80")
        assertTrue(portNumbers.contains(443), "Should have port 443")

        // Verify all ports are "open" (filtered correctly)
        assertTrue(ports.all { it.state == "open" }, "All ports should be 'open'")

        // Verify service/version are null (Masscan doesn't provide)
        assertTrue(ports.all { it.service == null }, "Service should be null")
        assertTrue(ports.all { it.version == null }, "Version should be null")

        // Verify timestamps on scan result
        assertNotNull(scanResult.discoveredAt)
        assertEquals(2025, scanResult.discoveredAt.year)

        // Cleanup
        assetRepository.delete(asset)
    }

    /**
     * Integration Test 2: Re-import same file updates existing asset
     *
     * Scenario:
     * 1. Upload testdata/masscan.xml first time
     * 2. Upload same file again
     * 3. Verify:
     *    - assetsCreated = 0, assetsUpdated = 1
     *    - lastSeen timestamp updated
     *    - New ScanResult records created (historical tracking, no deduplication)
     */
    @Test
    fun `testImportExistingAssets - Re-upload updates lastSeen creates duplicate scan results`() {
        // Arrange
        val token = authenticateAsUser()

        // Clean up
        val existingAsset = assetRepository.findByIp("193.99.144.85").firstOrNull()
        existingAsset?.let { assetRepository.delete(it) }

        val body = MultipartBody.builder()
            .addPart("xmlFile", testMasscanFile.name, MediaType.APPLICATION_XML_TYPE, testMasscanFile)
            .build()

        val request = HttpRequest.POST("/api/import/upload-masscan-xml", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        // Act - First upload
        val firstResponse = client.toBlocking().exchange(request, Map::class.java)
        assertEquals(HttpStatus.OK, firstResponse.status)

        val asset = assetRepository.findByIp("193.99.144.85").firstOrNull()
            ?: throw AssertionError("Asset should exist after first import")
        val initialScanResultCount = scanResultRepository.findByAssetIdOrderByDiscoveredAtDesc(asset.id!!).size

        // Act - Second upload (re-import)
        val secondResponse = client.toBlocking().exchange(request, Map::class.java)

        // Assert - HTTP response
        assertEquals(HttpStatus.OK, secondResponse.status)
        val secondImportResponse = secondResponse.body()!!

        assertEquals(0, (secondImportResponse["assetsCreated"] as Number).toInt(),
                     "Should not create new asset (already exists)")
        assertEquals(1, (secondImportResponse["assetsUpdated"] as Number).toInt(),
                     "Should update 1 existing asset")
        assertEquals(2, (secondImportResponse["portsImported"] as Number).toInt(),
                     "Should import 2 ports again")

        // Assert - Database state
        val updatedAsset = assetRepository.findByIp("193.99.144.85").firstOrNull()
            ?: throw AssertionError("Asset should still exist after second import")

        // Verify lastSeen was updated
        assertNotNull(updatedAsset.lastSeen)

        // Verify duplicate scan results created (historical tracking)
        // Each import creates one ScanResult (representing the host), not per port
        val finalScanResults = scanResultRepository.findByAssetIdOrderByDiscoveredAtDesc(updatedAsset.id!!)
        assertEquals(initialScanResultCount + 1, finalScanResults.size,
                     "Should have created 1 additional scan result (one per scan)")

        // Verify each scan result has 2 ports
        assertTrue(finalScanResults.all { it.ports.size == 2 },
                   "Each scan result should have 2 ports")

        // Cleanup
        assetRepository.delete(updatedAsset)
    }

    /**
     * Integration Test 3: Port state filtering
     *
     * Scenario:
     * 1. Upload XML with mixed port states (open, closed, filtered)
     * 2. Verify only "open" ports imported
     * 3. Verify "closed" and "filtered" ports skipped
     */
    @Test
    fun `testPortStateFiltering - Only open ports imported`() {
        // Arrange
        val token = authenticateAsUser()
        val mixedStatesFile = File.createTempFile("mixed", ".xml")
        mixedStatesFile.writeText("""
            <?xml version="1.0"?>
            <nmaprun scanner="masscan" start="1759560572">
                <host endtime="1759560572">
                    <address addr="10.0.0.1" addrtype="ipv4"/>
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
        """.trimIndent())

        val body = MultipartBody.builder()
            .addPart("xmlFile", mixedStatesFile.name, MediaType.APPLICATION_XML_TYPE, mixedStatesFile)
            .build()

        val request = HttpRequest.POST("/api/import/upload-masscan-xml", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val importResponse = response.body()!!

        assertEquals(2, (importResponse["portsImported"] as Number).toInt(),
                     "Should only import 2 open ports (80, 443)")

        val asset = assetRepository.findByIp("10.0.0.1").firstOrNull()
            ?: throw AssertionError("Asset should exist")
        val scanResults = scanResultRepository.findByAssetIdOrderByDiscoveredAtDesc(asset.id!!)

        assertEquals(1, scanResults.size, "Should have 1 scan result (1 host)")

        val ports = scanResults.first().ports
        val portNumbers = ports.map { it.portNumber }
        assertTrue(portNumbers.contains(80), "Should include port 80 (open)")
        assertTrue(portNumbers.contains(443), "Should include port 443 (open)")
        assertFalse(portNumbers.contains(81), "Should NOT include port 81 (closed)")
        assertFalse(portNumbers.contains(444), "Should NOT include port 444 (filtered)")

        // Cleanup
        assetRepository.delete(asset)
        mixedStatesFile.delete()
    }

    /**
     * Integration Test 4: Error handling
     *
     * Scenario:
     * 1. Upload malformed XML
     * 2. Verify 400 error response
     * 3. Verify no database changes
     */
    @Test
    fun `testErrorHandling - Invalid XML rejected no database changes`() {
        // Arrange
        val token = authenticateAsUser()
        val malformedFile = File.createTempFile("malformed", ".xml")
        malformedFile.writeText("not valid xml")

        val initialAssetCount = assetRepository.count()

        val body = MultipartBody.builder()
            .addPart("xmlFile", malformedFile.name, MediaType.APPLICATION_XML_TYPE, malformedFile)
            .build()

        val request = HttpRequest.POST("/api/import/upload-masscan-xml", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        // Act & Assert
        try {
            client.toBlocking().exchange(request, String::class.java)
            fail("Should have thrown exception for malformed XML")
        } catch (e: Exception) {
            // Expected - verify error response
            assertTrue(e.message!!.contains("400") || e.message!!.contains("Bad Request"))
        }

        // Verify no database changes
        assertEquals(initialAssetCount, assetRepository.count(),
                     "Asset count should not change after failed import")

        // Cleanup
        malformedFile.delete()
    }

    // Helper method for authentication
    private fun authenticateAsUser(): String {
        val credentials = UsernamePasswordCredentials("user", "user")
        val request = HttpRequest.POST("/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()!!["access_token"] as String
    }
}
