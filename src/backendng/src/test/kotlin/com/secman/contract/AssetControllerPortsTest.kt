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
 * Contract test for GET /api/assets/{id}/ports
 *
 * Tests the API contract defined in contracts/asset-ports.yaml:
 * - Retrieve port history for an asset
 * - Authenticated user access (not just ADMIN)
 * - Response format (PortHistoryDTO grouped by scan)
 * - 404 handling for non-existent assets
 *
 * Expected to FAIL until AssetController is enhanced (TDD red phase).
 */
@MicronautTest
class AssetControllerPortsTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    /**
     * Contract: GET /api/assets/{id}/ports with authenticated user
     * Expected Response: 200 OK with PortHistoryResponse containing:
     * - assetId: Long
     * - assetName: String
     * - scans: List<ScanPortsDTO> grouped by scan date
     */
    @Test
    fun `should return port history for asset with scan data`() {
        // Arrange
        val token = authenticateAsUser()
        val assetId = 1L // Assuming test data exists

        // Act
        val request = HttpRequest.GET<Any>("/api/assets/$assetId/ports")
            .bearerAuth(token)
        val response = client.toBlocking().exchange(request, PortHistoryResponse::class.java)

        // Assert - Contract validation
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        val portHistory = response.body()!!

        // Validate top-level structure
        assertEquals(assetId, portHistory.assetId, "assetId must match requested asset")
        assertNotNull(portHistory.assetName, "assetName must be present")
        assertNotNull(portHistory.scans, "scans list must be present")

        // Validate scans structure if data exists
        if (portHistory.scans.isNotEmpty()) {
            val scan = portHistory.scans.first()
            assertNotNull(scan.scanId, "scanId must be present")
            assertNotNull(scan.scanDate, "scanDate must be present")
            assertNotNull(scan.scanType, "scanType must be present")
            assertNotNull(scan.ports, "ports list must be present")

            // Validate port DTO structure if ports exist
            if (scan.ports.isNotEmpty()) {
                val port = scan.ports.first()
                assertTrue(port.portNumber in 1..65535, "portNumber must be in valid range 1-65535")
                assertNotNull(port.protocol, "protocol must be present")
                assertTrue(port.protocol in listOf("tcp", "udp"), "protocol must be tcp or udp")
                assertNotNull(port.state, "state must be present")
                assertTrue(port.state in listOf("open", "filtered", "closed"), "state must be open, filtered, or closed")
                // service and version can be null
            }
        }
    }

    /**
     * Contract: GET /api/assets/{id}/ports returns scans in reverse chronological order
     * (most recent first)
     */
    @Test
    fun `should return scans ordered by date descending`() {
        // Arrange
        val token = authenticateAsUser()
        val assetId = 1L

        // Act
        val request = HttpRequest.GET<Any>("/api/assets/$assetId/ports")
            .bearerAuth(token)
        val response = client.toBlocking().exchange(request, PortHistoryResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val portHistory = response.body()!!

        // Verify scans are ordered by scanDate descending (newest first)
        if (portHistory.scans.size > 1) {
            val scanDates = portHistory.scans.map { it.scanDate }
            val sortedDates = scanDates.sortedDescending()
            assertEquals(sortedDates, scanDates, "scans should be ordered by scanDate descending")
        }
    }

    /**
     * Contract: GET /api/assets/{id}/ports with admin authentication
     * Admin users should also have access (not exclusive to regular users)
     */
    @Test
    fun `should allow admin users to access port history`() {
        // Arrange
        val token = authenticateAsAdmin()
        val assetId = 1L

        // Act
        val request = HttpRequest.GET<Any>("/api/assets/$assetId/ports")
            .bearerAuth(token)
        val response = client.toBlocking().exchange(request, PortHistoryResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
    }

    /**
     * Contract: GET /api/assets/{id}/ports for asset with no scan data
     * Expected Response: 200 OK with empty scans list
     */
    @Test
    fun `should return empty scans list for asset without scan data`() {
        // Arrange
        val token = authenticateAsUser()
        val assetIdWithoutScans = 9999L // Assuming this asset exists but has no scans

        // Act
        val request = HttpRequest.GET<Any>("/api/assets/$assetIdWithoutScans/ports")
            .bearerAuth(token)
        val response = client.toBlocking().exchange(request, PortHistoryResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val portHistory = response.body()!!
        assertNotNull(portHistory.scans, "scans list must be present even if empty")
        assertTrue(portHistory.scans.isEmpty(), "scans list should be empty for asset without scan data")
    }

    /**
     * Contract: GET /api/assets/{id}/ports for non-existent asset
     * Expected Response: 404 NOT FOUND
     */
    @Test
    fun `should return 404 for non-existent asset`() {
        // Arrange
        val token = authenticateAsUser()
        val nonExistentId = 999999L

        // Act & Assert
        val request = HttpRequest.GET<Any>("/api/assets/$nonExistentId/ports")
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    /**
     * Contract: GET /api/assets/{id}/ports without authentication
     * Expected Response: 401 UNAUTHORIZED
     */
    @Test
    fun `should reject request without authentication`() {
        // Arrange
        val assetId = 1L

        // Act & Assert
        val request = HttpRequest.GET<Any>("/api/assets/$assetId/ports")

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    /**
     * Contract: GET /api/assets/{id}/ports with invalid ID format
     * Expected Response: 400 BAD REQUEST
     */
    @Test
    fun `should reject invalid asset ID format`() {
        // Arrange
        val token = authenticateAsUser()

        // Act & Assert
        val request = HttpRequest.GET<Any>("/api/assets/invalid-id/ports")
            .bearerAuth(token)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    /**
     * Contract: Verify port states are correctly represented
     * Tests that all valid port states (open, filtered, closed) are handled
     */
    @Test
    fun `should correctly represent all port states`() {
        // Arrange
        val token = authenticateAsUser()
        val assetId = 1L

        // Act
        val request = HttpRequest.GET<Any>("/api/assets/$assetId/ports")
            .bearerAuth(token)
        val response = client.toBlocking().exchange(request, PortHistoryResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        val portHistory = response.body()!!

        // Collect all unique port states
        val allStates = portHistory.scans
            .flatMap { it.ports }
            .map { it.state }
            .distinct()

        // Verify all states are valid
        val validStates = setOf("open", "filtered", "closed")
        assertTrue(allStates.all { it in validStates }, "all port states must be valid: $allStates")
    }

    // Helper methods
    private fun authenticateAsUser(): String {
        val credentials = UsernamePasswordCredentials("user", "user")
        val request = HttpRequest.POST("/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()!!["access_token"] as String
    }

    private fun authenticateAsAdmin(): String {
        val credentials = UsernamePasswordCredentials("admin", "admin")
        val request = HttpRequest.POST("/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()!!["access_token"] as String
    }

    // DTO classes matching contract specification
    data class PortHistoryResponse(
        val assetId: Long,
        val assetName: String,
        val scans: List<ScanPortsDTO>
    )

    data class ScanPortsDTO(
        val scanId: Long,
        val scanDate: String,
        val scanType: String,
        val ports: List<PortDTO>
    )

    data class PortDTO(
        val portNumber: Int,
        val protocol: String,
        val state: String,
        val service: String?,
        val version: String?
    )
}
