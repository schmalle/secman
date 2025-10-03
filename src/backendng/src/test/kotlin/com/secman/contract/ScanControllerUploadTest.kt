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
 * Contract test for POST /api/scan/upload-nmap
 *
 * Tests the API contract defined in contracts/upload-nmap.yaml:
 * - Multipart file upload with nmap XML
 * - Authentication/Authorization (ADMIN role required)
 * - Response format (ScanSummaryDTO)
 * - Error handling (400, 401, 413, 500)
 *
 * Expected to FAIL until ScanController is implemented (TDD red phase).
 */
@MicronautTest
class ScanControllerUploadTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    private val testNmapFile = File("src/test/resources/nmap-test.xml")

    /**
     * Contract: POST /api/scan/upload-nmap with valid nmap file and ADMIN auth
     * Expected Response: 200 OK with ScanSummaryDTO containing:
     * - scanId (Long)
     * - filename (String)
     * - scanDate (ISO 8601 timestamp)
     * - hostsDiscovered (Int)
     * - assetsCreated (Int)
     * - assetsUpdated (Int)
     * - totalPorts (Int)
     * - duration (String, format: "Xs" or "Xm Ys")
     */
    @Test
    fun `should accept valid nmap XML file with admin authentication`() {
        // Arrange
        val token = authenticateAsAdmin()
        val body = MultipartBody.builder()
            .addPart("file", testNmapFile.name, MediaType.APPLICATION_XML_TYPE, testNmapFile)
            .build()

        // Act
        val request = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val response = client.toBlocking().exchange(request, ScanSummaryResponse::class.java)

        // Assert - Contract validation
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        val summary = response.body()!!
        assertNotNull(summary.scanId, "scanId must be present")
        assertEquals(testNmapFile.name, summary.filename, "filename must match uploaded file")
        assertNotNull(summary.scanDate, "scanDate must be present")
        assertTrue(summary.hostsDiscovered >= 0, "hostsDiscovered must be non-negative")
        assertTrue(summary.assetsCreated >= 0, "assetsCreated must be non-negative")
        assertTrue(summary.assetsUpdated >= 0, "assetsUpdated must be non-negative")
        assertTrue(summary.totalPorts >= 0, "totalPorts must be non-negative")
        assertNotNull(summary.duration, "duration must be present")
        assertTrue(summary.duration.matches(Regex("\\d+s|\\d+m \\d+s")), "duration must match format 'Xs' or 'Xm Ys'")
    }

    /**
     * Contract: POST /api/scan/upload-nmap without authentication
     * Expected Response: 401 UNAUTHORIZED
     */
    @Test
    fun `should reject upload without authentication`() {
        // Arrange
        val body = MultipartBody.builder()
            .addPart("file", testNmapFile.name, MediaType.APPLICATION_XML_TYPE, testNmapFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    /**
     * Contract: POST /api/scan/upload-nmap with non-ADMIN role
     * Expected Response: 403 FORBIDDEN
     */
    @Test
    fun `should reject upload from non-admin user`() {
        // Arrange
        val token = authenticateAsUser()
        val body = MultipartBody.builder()
            .addPart("file", testNmapFile.name, MediaType.APPLICATION_XML_TYPE, testNmapFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    /**
     * Contract: POST /api/scan/upload-nmap with invalid XML
     * Expected Response: 400 BAD REQUEST with error details
     */
    @Test
    fun `should reject invalid XML file`() {
        // Arrange
        val token = authenticateAsAdmin()
        val invalidFile = File.createTempFile("invalid", ".xml").apply {
            writeText("<invalid>Not valid nmap XML</invalid>")
            deleteOnExit()
        }
        val body = MultipartBody.builder()
            .addPart("file", invalidFile.name, MediaType.APPLICATION_XML_TYPE, invalidFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    /**
     * Contract: POST /api/scan/upload-nmap with missing file
     * Expected Response: 400 BAD REQUEST
     */
    @Test
    fun `should reject request without file`() {
        // Arrange
        val token = authenticateAsAdmin()
        val body = MultipartBody.builder().build()

        // Act & Assert
        val request = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    /**
     * Contract: POST /api/scan/upload-nmap with oversized file (>10MB)
     * Expected Response: 413 PAYLOAD TOO LARGE
     */
    @Test
    fun `should reject file larger than 10MB`() {
        // Arrange
        val token = authenticateAsAdmin()
        val largeFile = File.createTempFile("large", ".xml").apply {
            writeBytes(ByteArray(11 * 1024 * 1024)) // 11MB
            deleteOnExit()
        }
        val body = MultipartBody.builder()
            .addPart("file", largeFile.name, MediaType.APPLICATION_XML_TYPE, largeFile)
            .build()

        // Act & Assert
        val request = HttpRequest.POST("/api/scan/upload-nmap", body)
            .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, exception.status)
    }

    // Helper methods for authentication
    private fun authenticateAsAdmin(): String {
        val credentials = UsernamePasswordCredentials("admin", "admin")
        val request = HttpRequest.POST("/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()!!["access_token"] as String
    }

    private fun authenticateAsUser(): String {
        val credentials = UsernamePasswordCredentials("user", "user")
        val request = HttpRequest.POST("/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()!!["access_token"] as String
    }

    // DTO class matching contract specification
    data class ScanSummaryResponse(
        val scanId: Long,
        val filename: String,
        val scanDate: String,
        val hostsDiscovered: Int,
        val assetsCreated: Int,
        val assetsUpdated: Int,
        val totalPorts: Int,
        val duration: String
    )
}
