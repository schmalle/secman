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
 * Contract test for GET /api/scans/{id} (detail endpoint)
 *
 * Tests the API contract defined in contracts/list-scans.yaml:
 * - Retrieve single scan with host details
 * - ADMIN-only access
 * - Response format (ScanDetailDTO with hosts list)
 * - 404 handling for non-existent scans
 *
 * Expected to FAIL until ScanController is implemented (TDD red phase).
 */
@MicronautTest
class ScanControllerDetailTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    /**
     * Contract: GET /api/scans/{id} with ADMIN authentication
     * Expected Response: 200 OK with ScanDetailDTO containing:
     * - Scan metadata (id, scanType, filename, scanDate, uploadedBy, hostCount, duration, createdAt)
     * - hosts: List<HostDTO> with ipAddress, hostname, discoveredAt, portCount
     */
    @Test
    fun `should return scan detail with hosts list for valid scan ID`() {
        // Arrange
        val token = authenticateAsAdmin()
        val scanId = 1L // Assuming test data exists

        // Act
        val request = HttpRequest.GET<Any>("/api/scans/$scanId")
            .bearerAuth(token)
        val response = client.toBlocking().exchange(request, ScanDetailResponse::class.java)

        // Assert - Contract validation
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        val scan = response.body()!!

        // Validate scan metadata
        assertEquals(scanId, scan.id, "id must match requested scan ID")
        assertNotNull(scan.scanType, "scanType must be present")
        assertNotNull(scan.filename, "filename must be present")
        assertNotNull(scan.scanDate, "scanDate must be present")
        assertNotNull(scan.uploadedBy, "uploadedBy must be present")
        assertTrue(scan.hostCount >= 0, "hostCount must be non-negative")
        assertNotNull(scan.duration, "duration must be present")
        assertNotNull(scan.createdAt, "createdAt must be present")

        // Validate hosts list
        assertNotNull(scan.hosts, "hosts list must be present")
        assertEquals(scan.hostCount, scan.hosts.size, "hosts list size must match hostCount")

        // Validate host DTO structure if hosts exist
        if (scan.hosts.isNotEmpty()) {
            val host = scan.hosts.first()
            assertNotNull(host.ipAddress, "ipAddress must be present")
            // hostname can be null (per FR-012)
            assertNotNull(host.discoveredAt, "discoveredAt must be present")
            assertTrue(host.portCount >= 0, "portCount must be non-negative")
        }
    }

    /**
     * Contract: GET /api/scans/{id} for non-existent scan
     * Expected Response: 404 NOT FOUND
     */
    @Test
    fun `should return 404 for non-existent scan ID`() {
        // Arrange
        val token = authenticateAsAdmin()
        val nonExistentId = 999999L

        // Act & Assert
        val request = HttpRequest.GET<Any>("/api/scans/$nonExistentId")
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    /**
     * Contract: GET /api/scans/{id} without authentication
     * Expected Response: 401 UNAUTHORIZED
     */
    @Test
    fun `should reject request without authentication`() {
        // Arrange
        val scanId = 1L

        // Act & Assert
        val request = HttpRequest.GET<Any>("/api/scans/$scanId")

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    /**
     * Contract: GET /api/scans/{id} with non-ADMIN role
     * Expected Response: 403 FORBIDDEN
     */
    @Test
    fun `should reject request from non-admin user`() {
        // Arrange
        val token = authenticateAsUser()
        val scanId = 1L

        // Act & Assert
        val request = HttpRequest.GET<Any>("/api/scans/$scanId")
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    /**
     * Contract: GET /api/scans/{id} with invalid ID format
     * Expected Response: 400 BAD REQUEST
     */
    @Test
    fun `should reject invalid scan ID format`() {
        // Arrange
        val token = authenticateAsAdmin()

        // Act & Assert
        val request = HttpRequest.GET<Any>("/api/scans/invalid-id")
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    /**
     * Contract: Verify host DTO contains hostname when available
     */
    @Test
    fun `should include hostname in host DTO when available`() {
        // Arrange
        val token = authenticateAsAdmin()
        val scanId = 1L

        // Act
        val request = HttpRequest.GET<Any>("/api/scans/$scanId")
            .bearerAuth(token)
        val response = client.toBlocking().exchange(request, ScanDetailResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val scan = response.body()!!

        // Find a host with hostname (if any)
        val hostWithHostname = scan.hosts.firstOrNull { it.hostname != null }
        if (hostWithHostname != null) {
            assertNotNull(hostWithHostname.hostname, "hostname should be present when available")
            assertTrue(hostWithHostname.hostname!!.isNotBlank(), "hostname should not be blank")
        }
    }

    /**
     * Contract: Verify scan detail returns hosts in order of discovery
     */
    @Test
    fun `should return hosts ordered by discovery time`() {
        // Arrange
        val token = authenticateAsAdmin()
        val scanId = 1L

        // Act
        val request = HttpRequest.GET<Any>("/api/scans/$scanId")
            .bearerAuth(token)
        val response = client.toBlocking().exchange(request, ScanDetailResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val scan = response.body()!!

        // Verify hosts are ordered by discoveredAt (chronological)
        if (scan.hosts.size > 1) {
            val discoveryTimes = scan.hosts.map { it.discoveredAt }
            val sortedTimes = discoveryTimes.sorted()
            assertEquals(sortedTimes, discoveryTimes, "hosts should be ordered by discoveredAt")
        }
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
    data class ScanDetailResponse(
        val id: Long,
        val scanType: String,
        val filename: String,
        val scanDate: String,
        val uploadedBy: String,
        val hostCount: Int,
        val duration: String,
        val createdAt: String,
        val hosts: List<HostDTO>
    )

    data class HostDTO(
        val ipAddress: String,
        val hostname: String?,
        val discoveredAt: String,
        val portCount: Int
    )
}
