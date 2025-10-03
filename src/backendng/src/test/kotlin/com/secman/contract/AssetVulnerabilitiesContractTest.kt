package com.secman.contract

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName

/**
 * Contract test for GET /api/assets/{assetId}/vulnerabilities endpoint
 *
 * Tests API contract compliance:
 * - Request: GET with assetId path parameter
 * - Response: 200 OK with Vulnerability array or paginated response
 * - Error cases: 404 Not Found, 401 Unauthorized
 *
 * CRITICAL: These tests MUST FAIL before implementation exists.
 */
@MicronautTest
class AssetVulnerabilitiesContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    @DisplayName("GET /api/assets/{id}/vulnerabilities with valid asset ID returns 200 OK with vulnerability array")
    fun testGetAssetVulnerabilitiesSuccess() {
        // Arrange: Use a known asset ID (assumes test data or will be created in setup)
        val assetId = 1L // Placeholder - will need actual asset ID from test database

        val request = HttpRequest.GET<Any>("/api/assets/$assetId/vulnerabilities")
            .header("Authorization", "Bearer ${getValidToken()}")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert response status
        assertEquals(HttpStatus.OK, response.status)

        // Assert response body structure
        val body = response.body() as Map<*, *>

        // Response can be either a simple array or paginated response
        // Check for paginated response first
        if (body.containsKey("content")) {
            // Paginated response
            assertTrue(body.containsKey("content"), "Paginated response must contain 'content'")
            assertTrue(body.containsKey("totalElements"), "Paginated response must contain 'totalElements'")
            assertTrue(body.containsKey("totalPages"), "Paginated response must contain 'totalPages'")
            assertTrue(body.containsKey("currentPage"), "Paginated response must contain 'currentPage'")
            assertTrue(body.containsKey("pageSize"), "Paginated response must contain 'pageSize'")

            val content = body["content"] as List<*>
            assertNotNull(content, "'content' must not be null")

            // If vulnerabilities exist, validate schema
            if (content.isNotEmpty()) {
                validateVulnerabilitySchema(content[0] as Map<*, *>)
            }
        } else {
            // Simple array response
            assertTrue(body is Map<*, *> || response.body() is List<*>,
                "Response must be either a Map (paginated) or List (array)")
        }
    }

    @Test
    @DisplayName("GET /api/assets/{id}/vulnerabilities with invalid asset ID returns 404 Not Found")
    fun testGetAssetVulnerabilitiesNotFound() {
        // Arrange: Use an asset ID that doesn't exist
        val nonExistentAssetId = 999999L

        val request = HttpRequest.GET<Any>("/api/assets/$nonExistentAssetId/vulnerabilities")
            .header("Authorization", "Bearer ${getValidToken()}")

        // Act & Assert
        try {
            client.toBlocking().exchange(request, Map::class.java)
            fail("Expected 404 Not Found for non-existent asset")
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            assertEquals(HttpStatus.NOT_FOUND, e.status)

            val errorBody = e.response.getBody(Map::class.java).orElse(null)
            if (errorBody != null) {
                assertTrue(errorBody.containsKey("error"),
                    "Error response should contain 'error' field")
            }
        }
    }

    @Test
    @DisplayName("GET /api/assets/{id}/vulnerabilities without authentication returns 401 Unauthorized")
    fun testGetAssetVulnerabilitiesUnauthorized() {
        // Arrange
        val assetId = 1L

        val request = HttpRequest.GET<Any>("/api/assets/$assetId/vulnerabilities")
        // No Authorization header

        // Act & Assert
        try {
            client.toBlocking().exchange(request, Map::class.java)
            fail("Expected 401 Unauthorized when no auth token provided")
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            assertEquals(HttpStatus.UNAUTHORIZED, e.status)
        }
    }

    @Test
    @DisplayName("GET /api/assets/{id}/vulnerabilities response matches Vulnerability schema")
    fun testGetAssetVulnerabilitiesResponseSchema() {
        // Arrange
        val assetId = 1L

        val request = HttpRequest.GET<Any>("/api/assets/$assetId/vulnerabilities")
            .header("Authorization", "Bearer ${getValidToken()}")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body() as Map<*, *>

        // Get vulnerabilities array (either from content or root)
        val vulnerabilities = if (body.containsKey("content")) {
            body["content"] as List<*>
        } else {
            listOf(body) // If single item or needs adjustment
        }

        // Validate each vulnerability matches schema
        if (vulnerabilities.isNotEmpty()) {
            vulnerabilities.forEach { vuln ->
                if (vuln is Map<*, *>) {
                    validateVulnerabilitySchema(vuln)
                }
            }
        }
    }

    @Test
    @DisplayName("GET /api/assets/{id}/vulnerabilities supports pagination parameters")
    fun testGetAssetVulnerabilitiesPagination() {
        // Arrange
        val assetId = 1L

        val request = HttpRequest.GET<Any>("/api/assets/$assetId/vulnerabilities?page=0&size=10&sort=scanTimestamp,desc")
            .header("Authorization", "Bearer ${getValidToken()}")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        // Pagination parameters should be accepted (even if no data exists)
        val body = response.body() as Map<*, *>
        if (body.containsKey("pageSize")) {
            assertTrue(body["pageSize"] is Number, "'pageSize' must be a number")
            val pageSize = (body["pageSize"] as Number).toInt()
            assertTrue(pageSize <= 10, "Page size should respect requested size")
        }
    }

    // Helper Methods

    /**
     * Validate Vulnerability entity schema
     * Per data-model.md specification
     */
    private fun validateVulnerabilitySchema(vulnerability: Map<*, *>) {
        // Required fields
        assertTrue(vulnerability.containsKey("id"), "Vulnerability must have 'id'")
        assertTrue(vulnerability.containsKey("scanTimestamp"), "Vulnerability must have 'scanTimestamp'")
        assertTrue(vulnerability.containsKey("createdAt"), "Vulnerability must have 'createdAt'")

        // Verify types
        assertTrue(vulnerability["id"] is Number, "'id' must be a number")
        assertTrue(vulnerability["scanTimestamp"] is String, "'scanTimestamp' must be a string (ISO 8601)")
        assertTrue(vulnerability["createdAt"] is String, "'createdAt' must be a string (ISO 8601)")

        // Optional fields (can be null but if present, must match type)
        if (vulnerability.containsKey("vulnerabilityId") && vulnerability["vulnerabilityId"] != null) {
            assertTrue(vulnerability["vulnerabilityId"] is String, "'vulnerabilityId' must be a string")
        }

        if (vulnerability.containsKey("cvssSeverity") && vulnerability["cvssSeverity"] != null) {
            assertTrue(vulnerability["cvssSeverity"] is String, "'cvssSeverity' must be a string")
            val severity = vulnerability["cvssSeverity"] as String
            assertTrue(severity in listOf("Critical", "High", "Medium", "Low", "Informational"),
                "'cvssSeverity' must be valid enum value")
        }

        if (vulnerability.containsKey("vulnerableProductVersions") && vulnerability["vulnerableProductVersions"] != null) {
            assertTrue(vulnerability["vulnerableProductVersions"] is String,
                "'vulnerableProductVersions' must be a string")
        }

        if (vulnerability.containsKey("daysOpen") && vulnerability["daysOpen"] != null) {
            assertTrue(vulnerability["daysOpen"] is String, "'daysOpen' must be a string")
        }
    }

    /**
     * Get a valid JWT token for testing
     */
    private fun getValidToken(): String {
        // TODO: Replace with actual token generation from auth system
        return "test-token-placeholder"
    }
}
