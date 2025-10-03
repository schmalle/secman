package com.secman.contract

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.security.authentication.UsernamePasswordCredentials
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Contract test for GET /api/scans (list endpoint)
 *
 * Tests the API contract defined in contracts/list-scans.yaml:
 * - Pagination support (page, size query parameters)
 * - ADMIN-only access
 * - Response format (paginated scan list)
 * - Filtering by scan type
 *
 * Expected to FAIL until ScanController is implemented (TDD red phase).
 */
@MicronautTest
class ScanControllerListTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    /**
     * Contract: GET /api/scans with ADMIN authentication
     * Expected Response: 200 OK with paginated scan list containing:
     * - content: List<ScanDTO>
     * - totalElements: Long
     * - totalPages: Int
     * - size: Int
     * - number: Int (current page)
     */
    @Test
    fun `should return paginated scan list for admin user`() {
        // Arrange
        val token = authenticateAsAdmin()

        // Act
        val request = HttpRequest.GET<Any>("/api/scans?page=0&size=10")
            .bearerAuth(token)
        val response = client.toBlocking().exchange(request, PagedScanResponse::class.java)

        // Assert - Contract validation
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        val body = response.body()!!
        assertNotNull(body.content, "content must be present")
        assertTrue(body.totalElements >= 0, "totalElements must be non-negative")
        assertTrue(body.totalPages >= 0, "totalPages must be non-negative")
        assertEquals(10, body.size, "size must match requested page size")
        assertEquals(0, body.number, "number must match requested page number")
    }

    /**
     * Contract: GET /api/scans with default pagination (no params)
     * Expected Response: 200 OK with default page size 20, page 0
     */
    @Test
    fun `should use default pagination when parameters not provided`() {
        // Arrange
        val token = authenticateAsAdmin()

        // Act
        val request = HttpRequest.GET<Any>("/api/scans")
            .bearerAuth(token)
        val response = client.toBlocking().exchange(request, PagedScanResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val body = response.body()!!
        assertEquals(20, body.size, "default page size should be 20")
        assertEquals(0, body.number, "default page number should be 0")
    }

    /**
     * Contract: GET /api/scans with scanType filter
     * Expected Response: 200 OK with filtered results
     */
    @Test
    fun `should filter scans by scan type`() {
        // Arrange
        val token = authenticateAsAdmin()

        // Act
        val request = HttpRequest.GET<Any>("/api/scans?scanType=nmap")
            .bearerAuth(token)
        val response = client.toBlocking().exchange(request, PagedScanResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val body = response.body()!!

        // Verify all returned scans have the correct type
        body.content.forEach { scan ->
            assertEquals("nmap", scan.scanType, "all scans should have scanType=nmap")
        }
    }

    /**
     * Contract: GET /api/scans validates scan DTO structure
     * Each ScanDTO must contain:
     * - id, scanType, filename, scanDate, uploadedBy, hostCount, duration, createdAt
     */
    @Test
    fun `should return scans with complete DTO structure`() {
        // Arrange
        val token = authenticateAsAdmin()

        // Act
        val request = HttpRequest.GET<Any>("/api/scans?size=1")
            .bearerAuth(token)
        val response = client.toBlocking().exchange(request, PagedScanResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val body = response.body()!!

        if (body.content.isNotEmpty()) {
            val scan = body.content.first()
            assertNotNull(scan.id, "id must be present")
            assertNotNull(scan.scanType, "scanType must be present")
            assertNotNull(scan.filename, "filename must be present")
            assertNotNull(scan.scanDate, "scanDate must be present")
            assertNotNull(scan.uploadedBy, "uploadedBy must be present")
            assertTrue(scan.hostCount >= 0, "hostCount must be non-negative")
            assertNotNull(scan.duration, "duration must be present")
            assertNotNull(scan.createdAt, "createdAt must be present")
        }
    }

    /**
     * Contract: GET /api/scans without authentication
     * Expected Response: 401 UNAUTHORIZED
     */
    @Test
    fun `should reject request without authentication`() {
        // Act & Assert
        val request = HttpRequest.GET<Any>("/api/scans")

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    /**
     * Contract: GET /api/scans with non-ADMIN role
     * Expected Response: 403 FORBIDDEN
     */
    @Test
    fun `should reject request from non-admin user`() {
        // Arrange
        val token = authenticateAsUser()

        // Act & Assert
        val request = HttpRequest.GET<Any>("/api/scans")
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    /**
     * Contract: GET /api/scans with invalid pagination parameters
     * Expected Response: 400 BAD REQUEST
     */
    @Test
    fun `should reject invalid pagination parameters`() {
        // Arrange
        val token = authenticateAsAdmin()

        // Act & Assert - negative page number
        val request1 = HttpRequest.GET<Any>("/api/scans?page=-1")
            .bearerAuth(token)

        val exception1 = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request1, String::class.java)
        }
        assertEquals(HttpStatus.BAD_REQUEST, exception1.status)

        // Act & Assert - invalid size
        val request2 = HttpRequest.GET<Any>("/api/scans?size=0")
            .bearerAuth(token)

        val exception2 = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request2, String::class.java)
        }
        assertEquals(HttpStatus.BAD_REQUEST, exception2.status)
    }

    // Helper methods
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

    // DTO classes matching contract specification
    data class PagedScanResponse(
        val content: List<ScanDTO>,
        val totalElements: Long,
        val totalPages: Int,
        val size: Int,
        val number: Int
    )

    data class ScanDTO(
        val id: Long,
        val scanType: String,
        val filename: String,
        val scanDate: String,
        val uploadedBy: String,
        val hostCount: Int,
        val duration: String,
        val createdAt: String
    )
}
