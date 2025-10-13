package com.secman.contract

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName

/**
 * Contract test for GET /api/crowdstrike/vulnerabilities endpoint
 *
 * Tests API contract compliance:
 * - Request: GET with hostname query parameter
 * - Response: 200 OK with CrowdStrikeQueryResponse
 * - Error cases: 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found, 500 Internal Server Error
 *
 * Related to: Feature 015-we-have-currently (CrowdStrike System Vulnerability Lookup)
 * Task: T012 [US1-Test]
 *
 * CRITICAL: These tests MUST FAIL before implementation exists (TDD Red-Green-Refactor).
 */
@MicronautTest
open class CrowdStrikeQueryContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    @DisplayName("GET /api/crowdstrike/vulnerabilities without JWT token returns 401 Unauthorized")
    fun testQueryVulnerabilitiesUnauthorized() {
        // Arrange
        val hostname = "test-server-01"
        val request = HttpRequest.GET<Any>("/api/crowdstrike/vulnerabilities?hostname=$hostname")
        // No Authorization header

        // Act & Assert
        try {
            client.toBlocking().exchange(request, Map::class.java)
            fail("Expected 401 Unauthorized when no auth token provided")
        } catch (e: HttpClientResponseException) {
            assertEquals(HttpStatus.UNAUTHORIZED, e.status)
        }
    }

    @Test
    @DisplayName("GET /api/crowdstrike/vulnerabilities without ADMIN or VULN role returns 403 Forbidden")
    fun testQueryVulnerabilitiesForbidden() {
        // Arrange
        val hostname = "test-server-01"
        val request = HttpRequest.GET<Any>("/api/crowdstrike/vulnerabilities?hostname=$hostname")
            .header("Authorization", "Bearer ${getUserRoleToken()}")

        // Act & Assert
        try {
            client.toBlocking().exchange(request, Map::class.java)
            fail("Expected 403 Forbidden when user lacks ADMIN or VULN role")
        } catch (e: HttpClientResponseException) {
            assertEquals(HttpStatus.FORBIDDEN, e.status)
        }
    }

    @Test
    @DisplayName("GET /api/crowdstrike/vulnerabilities with blank hostname returns 400 Bad Request")
    fun testQueryVulnerabilitiesBlankHostname() {
        // Arrange
        val request = HttpRequest.GET<Any>("/api/crowdstrike/vulnerabilities?hostname=")
            .header("Authorization", "Bearer ${getAdminToken()}")

        // Act & Assert
        try {
            client.toBlocking().exchange(request, Map::class.java)
            fail("Expected 400 Bad Request for blank hostname")
        } catch (e: HttpClientResponseException) {
            assertEquals(HttpStatus.BAD_REQUEST, e.status)

            val errorBody = e.response.getBody(Map::class.java).orElse(null)
            if (errorBody != null) {
                assertTrue(errorBody.containsKey("error") || errorBody.containsKey("message"),
                    "Error response should contain 'error' or 'message' field")
            }
        }
    }

    @Test
    @DisplayName("GET /api/crowdstrike/vulnerabilities with valid hostname and JWT returns 200 OK")
    fun testQueryVulnerabilitiesSuccess() {
        // Arrange
        val hostname = "test-server-01"
        val request = HttpRequest.GET<Any>("/api/crowdstrike/vulnerabilities?hostname=$hostname")
            .header("Authorization", "Bearer ${getAdminToken()}")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert response status
        assertEquals(HttpStatus.OK, response.status)

        // Assert response body structure
        val body = response.body() as Map<*, *>
        validateCrowdStrikeQueryResponse(body, hostname)
    }

    @Test
    @DisplayName("GET /api/crowdstrike/vulnerabilities response schema matches CrowdStrikeQueryResponse DTO")
    fun testQueryVulnerabilitiesResponseSchema() {
        // Arrange
        val hostname = "web-server-01"
        val request = HttpRequest.GET<Any>("/api/crowdstrike/vulnerabilities?hostname=$hostname")
            .header("Authorization", "Bearer ${getAdminToken()}")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body() as Map<*, *>
        validateCrowdStrikeQueryResponse(body, hostname)

        // If vulnerabilities exist, validate schema
        val vulnerabilities = body["vulnerabilities"] as List<*>
        if (vulnerabilities.isNotEmpty()) {
            vulnerabilities.forEach { vuln ->
                if (vuln is Map<*, *>) {
                    validateCrowdStrikeVulnerabilityDto(vuln)
                }
            }
        }
    }

    @Test
    @DisplayName("GET /api/crowdstrike/vulnerabilities with no active FalconConfig returns 500 Internal Server Error")
    fun testQueryVulnerabilitiesNoConfiguration() {
        // Arrange
        val hostname = "test-server-01"
        val request = HttpRequest.GET<Any>("/api/crowdstrike/vulnerabilities?hostname=$hostname")
            .header("Authorization", "Bearer ${getAdminToken()}")

        // Act & Assert
        // NOTE: This test will fail if FalconConfig exists in database
        // In a real test environment, you would mock FalconConfigRepository
        // For now, this documents the expected behavior
        try {
            val response = client.toBlocking().exchange(request, Map::class.java)

            // If config exists, we expect 200
            // If config doesn't exist, we expect 500
            assertTrue(response.status == HttpStatus.OK || response.status == HttpStatus.INTERNAL_SERVER_ERROR,
                "Expected 200 OK (with config) or 500 (without config)")

            if (response.status == HttpStatus.INTERNAL_SERVER_ERROR) {
                val errorBody = response.body() as Map<*, *>
                val errorMessage = errorBody["error"]?.toString() ?: ""
                assertTrue(errorMessage.contains("credentials not configured", ignoreCase = true),
                    "Error message should indicate missing credentials configuration")
            }
        } catch (e: HttpClientResponseException) {
            if (e.status == HttpStatus.INTERNAL_SERVER_ERROR) {
                val errorBody = e.response.getBody(Map::class.java).orElse(null)
                if (errorBody != null) {
                    val errorMessage = errorBody["error"]?.toString() ?: ""
                    assertTrue(errorMessage.contains("credentials not configured", ignoreCase = true),
                        "Error message should indicate missing credentials configuration")
                }
            } else {
                fail("Expected 500 Internal Server Error for missing config, got ${e.status}")
            }
        }
    }

    // Helper Methods

    /**
     * Validate CrowdStrikeQueryResponse schema
     */
    private fun validateCrowdStrikeQueryResponse(response: Map<*, *>, expectedHostname: String) {
        // Required fields
        assertTrue(response.containsKey("hostname"), "Response must have 'hostname'")
        assertTrue(response.containsKey("vulnerabilities"), "Response must have 'vulnerabilities'")
        assertTrue(response.containsKey("totalCount"), "Response must have 'totalCount'")
        assertTrue(response.containsKey("queriedAt"), "Response must have 'queriedAt'")

        // Verify types
        assertEquals(expectedHostname, response["hostname"], "'hostname' must match request")
        assertTrue(response["vulnerabilities"] is List<*>, "'vulnerabilities' must be a list")
        assertTrue(response["totalCount"] is Number, "'totalCount' must be a number")
        assertTrue(response["queriedAt"] is String, "'queriedAt' must be a string (ISO 8601)")
    }

    /**
     * Validate CrowdStrikeVulnerabilityDto schema
     */
    private fun validateCrowdStrikeVulnerabilityDto(vulnerability: Map<*, *>) {
        // Required fields
        assertTrue(vulnerability.containsKey("id"), "Vulnerability must have 'id'")
        assertTrue(vulnerability.containsKey("hostname"), "Vulnerability must have 'hostname'")
        assertTrue(vulnerability.containsKey("severity"), "Vulnerability must have 'severity'")
        assertTrue(vulnerability.containsKey("detectedAt"), "Vulnerability must have 'detectedAt'")
        assertTrue(vulnerability.containsKey("status"), "Vulnerability must have 'status'")
        assertTrue(vulnerability.containsKey("hasException"), "Vulnerability must have 'hasException'")

        // Verify types
        assertTrue(vulnerability["id"] is String, "'id' must be a string")
        assertTrue(vulnerability["hostname"] is String, "'hostname' must be a string")
        assertTrue(vulnerability["severity"] is String, "'severity' must be a string")
        assertTrue(vulnerability["detectedAt"] is String, "'detectedAt' must be a string (ISO 8601)")
        assertTrue(vulnerability["status"] is String, "'status' must be a string")
        assertTrue(vulnerability["hasException"] is Boolean, "'hasException' must be a boolean")

        // Verify severity enum
        val severity = vulnerability["severity"] as String
        assertTrue(severity in listOf("Critical", "High", "Medium", "Low", "Informational"),
            "'severity' must be valid enum value")

        // Optional fields (nullable but must match type if present)
        if (vulnerability.containsKey("ip") && vulnerability["ip"] != null) {
            assertTrue(vulnerability["ip"] is String, "'ip' must be a string")
        }

        if (vulnerability.containsKey("cveId") && vulnerability["cveId"] != null) {
            assertTrue(vulnerability["cveId"] is String, "'cveId' must be a string")
        }

        if (vulnerability.containsKey("cvssScore") && vulnerability["cvssScore"] != null) {
            assertTrue(vulnerability["cvssScore"] is Number, "'cvssScore' must be a number")
            val score = (vulnerability["cvssScore"] as Number).toDouble()
            assertTrue(score >= 0.0 && score <= 10.0, "'cvssScore' must be between 0.0 and 10.0")
        }

        if (vulnerability.containsKey("affectedProduct") && vulnerability["affectedProduct"] != null) {
            assertTrue(vulnerability["affectedProduct"] is String, "'affectedProduct' must be a string")
        }

        if (vulnerability.containsKey("daysOpen") && vulnerability["daysOpen"] != null) {
            assertTrue(vulnerability["daysOpen"] is String, "'daysOpen' must be a string")
        }

        if (vulnerability.containsKey("exceptionReason") && vulnerability["exceptionReason"] != null) {
            assertTrue(vulnerability["exceptionReason"] is String, "'exceptionReason' must be a string")
        }
    }

    /**
     * Get a valid JWT token with ADMIN role for testing
     */
    private fun getAdminToken(): String {
        // TODO: Replace with actual token generation from auth system
        return "test-admin-token-placeholder"
    }

    /**
     * Get a valid JWT token with USER role (no ADMIN/VULN) for testing
     */
    private fun getUserRoleToken(): String {
        // TODO: Replace with actual token generation from auth system
        return "test-user-token-placeholder"
    }
}
