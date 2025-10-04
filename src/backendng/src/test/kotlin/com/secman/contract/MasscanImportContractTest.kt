package com.secman.contract

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.security.authentication.UsernamePasswordCredentials
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Contract test for POST /api/import/upload-masscan-xml
 *
 * Related to: Feature 005-add-funtionality-to (Masscan XML Import)
 *
 * Tests the API contract defined in contracts/api-contract.yaml:
 * - Multipart file upload with Masscan XML
 * - Authentication required (any authenticated user)
 * - Response format (MasscanImportResponse)
 * - Error handling (400, 401, 500)
 *
 * Expected to FAIL until ImportController.uploadMasscanXml() is implemented (TDD red phase).
 */
@MicronautTest
class MasscanImportContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    private val testMasscanFile = File("testdata/masscan.xml")

    /**
     * Contract: POST /api/import/upload-masscan-xml with valid Masscan file
     * Expected Response: 200 OK with MasscanImportResponse containing:
     * - message (String)
     * - assetsCreated (Int)
     * - assetsUpdated (Int)
     * - portsImported (Int)
     */
    @Test
    fun `testUploadValidMasscanXml - Returns 200 with correct import counts`() {
        // Arrange
        val token = authenticateAsUser()
        val body = MultipartBody.builder()
            .addPart("xmlFile", testMasscanFile.name, MediaType.APPLICATION_XML_TYPE, testMasscanFile)
            .build()

        // Act
        val request = HttpRequest.POST("/api/import/upload-masscan-xml", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, MasscanImportResponse::class.java)

        // Assert - Contract validation
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        val importResponse = response.body()!!
        assertNotNull(importResponse.message, "message must be present")
        assertTrue(importResponse.assetsCreated >= 0, "assetsCreated must be non-negative")
        assertTrue(importResponse.assetsUpdated >= 0, "assetsUpdated must be non-negative")
        assertTrue(importResponse.portsImported >= 0, "portsImported must be non-negative")

        // Verify counts make sense
        val totalAssets = importResponse.assetsCreated + importResponse.assetsUpdated
        assertTrue(totalAssets > 0 || importResponse.portsImported == 0,
                   "If ports imported, should have assets")
    }

    /**
     * Contract: POST /api/import/upload-masscan-xml with .txt file (wrong extension)
     * Expected Response: 400 BAD REQUEST with error message
     */
    @Test
    fun `testUploadInvalidExtension - Returns 400 for non-xml file`() {
        // Arrange
        val token = authenticateAsUser()
        val txtFile = File.createTempFile("test", ".txt")
        txtFile.writeText("not xml")

        val body = MultipartBody.builder()
            .addPart("xmlFile", txtFile.name, MediaType.TEXT_PLAIN_TYPE, txtFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/import/upload-masscan-xml", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        assertTrue(exception.message!!.contains("xml") || exception.message!!.contains("extension"),
                   "Error message should mention XML or extension requirement")

        // Cleanup
        txtFile.delete()
    }

    /**
     * Contract: POST /api/import/upload-masscan-xml with oversized file (>10MB)
     * Expected Response: 400 BAD REQUEST with size error
     */
    @Test
    fun `testUploadOversizedFile - Returns 400 for file exceeding 10MB`() {
        // Arrange
        val token = authenticateAsUser()
        // Create a file larger than 10MB (11MB of dummy data)
        val largeFile = File.createTempFile("large", ".xml")
        largeFile.writeText("<?xml version=\"1.0\"?><nmaprun scanner=\"masscan\"><host>" +
                           "x".repeat(11 * 1024 * 1024) + "</host></nmaprun>")

        val body = MultipartBody.builder()
            .addPart("xmlFile", largeFile.name, MediaType.APPLICATION_XML_TYPE, largeFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/import/upload-masscan-xml", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        assertTrue(exception.message!!.contains("size") || exception.message!!.contains("10MB"),
                   "Error message should mention file size limit")

        // Cleanup
        largeFile.delete()
    }

    /**
     * Contract: POST /api/import/upload-masscan-xml with malformed XML
     * Expected Response: 400 BAD REQUEST with parse error
     */
    @Test
    fun `testUploadMalformedXml - Returns 400 with clear error`() {
        // Arrange
        val token = authenticateAsUser()
        val malformedFile = File.createTempFile("malformed", ".xml")
        malformedFile.writeText("""
            <?xml version="1.0"?>
            <nmaprun scanner="masscan" start="123">
                <host endtime="123">
                    <address addr="192.168.1.1"
                </host>
        """.trimIndent())

        val body = MultipartBody.builder()
            .addPart("xmlFile", malformedFile.name, MediaType.APPLICATION_XML_TYPE, malformedFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/import/upload-masscan-xml", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        assertTrue(exception.message!!.contains("Invalid") || exception.message!!.contains("format"),
                   "Error message should indicate invalid format")

        // Cleanup
        malformedFile.delete()
    }

    /**
     * Contract: POST /api/import/upload-masscan-xml with Nmap XML (wrong scanner)
     * Expected Response: 400 BAD REQUEST with scanner error
     */
    @Test
    fun `testUploadNmapXml - Returns 400 for wrong scanner type`() {
        // Arrange
        val token = authenticateAsUser()
        val nmapFile = File.createTempFile("nmap", ".xml")
        nmapFile.writeText("""
            <?xml version="1.0"?>
            <nmaprun scanner="nmap" start="1234567890">
                <host>
                    <address addr="192.168.1.1" addrtype="ipv4"/>
                    <ports>
                        <port protocol="tcp" portid="80">
                            <state state="open"/>
                        </port>
                    </ports>
                </host>
            </nmaprun>
        """.trimIndent())

        val body = MultipartBody.builder()
            .addPart("xmlFile", nmapFile.name, MediaType.APPLICATION_XML_TYPE, nmapFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/import/upload-masscan-xml", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        assertTrue(exception.message!!.contains("Not a Masscan") || exception.message!!.contains("scanner"),
                   "Error message should identify wrong scanner type")

        // Cleanup
        nmapFile.delete()
    }

    /**
     * Contract: POST /api/import/upload-masscan-xml without authentication
     * Expected Response: 401 UNAUTHORIZED
     */
    @Test
    fun `testUploadWithoutAuth - Returns 401 unauthorized`() {
        // Arrange
        val body = MultipartBody.builder()
            .addPart("xmlFile", testMasscanFile.name, MediaType.APPLICATION_XML_TYPE, testMasscanFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/import/upload-masscan-xml", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
        // No bearerAuth() - unauthenticated

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    /**
     * Contract: Verify response structure matches schema
     * All fields present and correct types
     */
    @Test
    fun `testResponseStructure - Verify JSON schema compliance`() {
        // Arrange
        val token = authenticateAsUser()
        val requestBody = MultipartBody.builder()
            .addPart("xmlFile", testMasscanFile.name, MediaType.APPLICATION_XML_TYPE, testMasscanFile)
            .build()

        // Act
        val request = HttpRequest.POST("/api/import/upload-masscan-xml", requestBody)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert - Verify all required fields exist
        val body = response.body()!!
        assertTrue(body.containsKey("message"), "Response must have 'message' field")
        assertTrue(body.containsKey("assetsCreated"), "Response must have 'assetsCreated' field")
        assertTrue(body.containsKey("assetsUpdated"), "Response must have 'assetsUpdated' field")
        assertTrue(body.containsKey("portsImported"), "Response must have 'portsImported' field")

        // Verify types
        assertTrue(body["message"] is String, "message must be String")
        assertTrue(body["assetsCreated"] is Number, "assetsCreated must be Number")
        assertTrue(body["assetsUpdated"] is Number, "assetsUpdated must be Number")
        assertTrue(body["portsImported"] is Number, "portsImported must be Number")
    }

    // Helper methods for authentication
    private fun authenticateAsUser(): String {
        val credentials = UsernamePasswordCredentials("user", "user")
        val request = HttpRequest.POST("/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()!!["access_token"] as String
    }

    // DTO class matching contract specification
    data class MasscanImportResponse(
        val message: String,
        val assetsCreated: Int,
        val assetsUpdated: Int,
        val portsImported: Int
    )
}
