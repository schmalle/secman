package com.secman.integration

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
 * Integration test for VULN role authorization
 *
 * Tests that the vulnerability management endpoints enforce proper RBAC:
 * - USER role: Cannot access any vulnerability management endpoints (403 FORBIDDEN)
 * - ADMIN role: Can access all vulnerability management endpoints (200 OK)
 * - VULN role: Can access all vulnerability management endpoints (200 OK)
 * - Unauthenticated: Cannot access endpoints (401 UNAUTHORIZED)
 *
 * Endpoints tested:
 * - GET /api/vulnerabilities/current
 * - GET /api/vulnerability-exceptions
 * - POST /api/vulnerability-exceptions
 * - PUT /api/vulnerability-exceptions/{id}
 * - DELETE /api/vulnerability-exceptions/{id}
 *
 * Expected to FAIL until VulnerabilityManagementController implements proper @Secured annotations (TDD red phase).
 *
 * Related to: Feature 004-i-want-to (VULN Role & Vulnerability Management UI)
 * Task: T010 (Integration Test - VulnRoleAuthorizationTest)
 */
@MicronautTest
class VulnRoleAuthorizationTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    // ============================================================================
    // Test: Normal USER cannot access vulnerability endpoints
    // ============================================================================

    @Test
    fun `testNormalUserCannotAccessVulnEndpoints - should return 403 for all 5 endpoints with USER role`() {
        // Arrange
        val userToken = authenticateAsUser()

        // Test 1: GET /api/vulnerabilities/current
        val getCurrentRequest = HttpRequest.GET<Any>("/api/vulnerabilities/current")
            .bearerAuth(userToken)

        val exception1 = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(getCurrentRequest, String::class.java)
        }
        assertEquals(HttpStatus.FORBIDDEN, exception1.status,
            "USER should get 403 FORBIDDEN for GET /api/vulnerabilities/current")

        // Test 2: GET /api/vulnerability-exceptions
        val getExceptionsRequest = HttpRequest.GET<Any>("/api/vulnerability-exceptions")
            .bearerAuth(userToken)

        val exception2 = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(getExceptionsRequest, String::class.java)
        }
        assertEquals(HttpStatus.FORBIDDEN, exception2.status,
            "USER should get 403 FORBIDDEN for GET /api/vulnerability-exceptions")

        // Test 3: POST /api/vulnerability-exceptions
        val createExceptionBody = mapOf(
            "exceptionType" to "PRODUCT",
            "targetValue" to "OpenSSH 7.4",
            "reason" to "Test reason"
        )
        val postExceptionRequest = HttpRequest.POST("/api/vulnerability-exceptions", createExceptionBody)
            .bearerAuth(userToken)

        val exception3 = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(postExceptionRequest, String::class.java)
        }
        assertEquals(HttpStatus.FORBIDDEN, exception3.status,
            "USER should get 403 FORBIDDEN for POST /api/vulnerability-exceptions")

        // Test 4: PUT /api/vulnerability-exceptions/{id}
        val updateExceptionBody = mapOf(
            "exceptionType" to "IP",
            "targetValue" to "192.168.1.10",
            "reason" to "Updated reason"
        )
        val putExceptionRequest = HttpRequest.PUT("/api/vulnerability-exceptions/1", updateExceptionBody)
            .bearerAuth(userToken)

        val exception4 = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(putExceptionRequest, String::class.java)
        }
        assertEquals(HttpStatus.FORBIDDEN, exception4.status,
            "USER should get 403 FORBIDDEN for PUT /api/vulnerability-exceptions/{id}")

        // Test 5: DELETE /api/vulnerability-exceptions/{id}
        val deleteExceptionRequest = HttpRequest.DELETE<Any>("/api/vulnerability-exceptions/1")
            .bearerAuth(userToken)

        val exception5 = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(deleteExceptionRequest, String::class.java)
        }
        assertEquals(HttpStatus.FORBIDDEN, exception5.status,
            "USER should get 403 FORBIDDEN for DELETE /api/vulnerability-exceptions/{id}")
    }

    // ============================================================================
    // Test: ADMIN can access all vulnerability endpoints
    // ============================================================================

    @Test
    fun `testAdminCanAccessVulnEndpoints - should return 200 for all endpoints with ADMIN role`() {
        // Arrange
        val adminToken = authenticateAsAdmin()

        // Test 1: GET /api/vulnerabilities/current
        val getCurrentRequest = HttpRequest.GET<Any>("/api/vulnerabilities/current")
            .bearerAuth(adminToken)

        val response1 = client.toBlocking().exchange(getCurrentRequest, String::class.java)
        assertEquals(HttpStatus.OK, response1.status,
            "ADMIN should get 200 OK for GET /api/vulnerabilities/current")

        // Test 2: GET /api/vulnerability-exceptions
        val getExceptionsRequest = HttpRequest.GET<Any>("/api/vulnerability-exceptions")
            .bearerAuth(adminToken)

        val response2 = client.toBlocking().exchange(getExceptionsRequest, String::class.java)
        assertEquals(HttpStatus.OK, response2.status,
            "ADMIN should get 200 OK for GET /api/vulnerability-exceptions")

        // Test 3: POST /api/vulnerability-exceptions
        val createExceptionBody = mapOf(
            "exceptionType" to "PRODUCT",
            "targetValue" to "OpenSSH 7.4",
            "reason" to "Test reason for admin"
        )
        val postExceptionRequest = HttpRequest.POST("/api/vulnerability-exceptions", createExceptionBody)
            .bearerAuth(adminToken)

        val response3 = client.toBlocking().exchange(postExceptionRequest, String::class.java)
        assertEquals(HttpStatus.CREATED, response3.status,
            "ADMIN should get 201 CREATED for POST /api/vulnerability-exceptions")

        // Test 4: PUT /api/vulnerability-exceptions/{id}
        // First get the ID from the created exception (extract from response3)
        // For simplicity, we'll test with a non-existent ID and verify we don't get 403
        val updateExceptionBody = mapOf(
            "exceptionType" to "IP",
            "targetValue" to "192.168.1.10",
            "reason" to "Updated reason by admin"
        )
        val putExceptionRequest = HttpRequest.PUT("/api/vulnerability-exceptions/99999", updateExceptionBody)
            .bearerAuth(adminToken)

        try {
            val response4 = client.toBlocking().exchange(putExceptionRequest, String::class.java)
            // If successful, should be 200 OK
            assertEquals(HttpStatus.OK, response4.status,
                "ADMIN should get 200 OK for PUT /api/vulnerability-exceptions/{id}")
        } catch (e: HttpClientResponseException) {
            // If not found, should be 404, NOT 403
            assertEquals(HttpStatus.NOT_FOUND, e.status,
                "ADMIN should get 404 NOT FOUND (not 403 FORBIDDEN) for PUT with non-existent ID")
        }

        // Test 5: DELETE /api/vulnerability-exceptions/{id}
        val deleteExceptionRequest = HttpRequest.DELETE<Any>("/api/vulnerability-exceptions/99999")
            .bearerAuth(adminToken)

        try {
            val response5 = client.toBlocking().exchange(deleteExceptionRequest, String::class.java)
            // If successful, should be 204 NO CONTENT
            assertEquals(HttpStatus.NO_CONTENT, response5.status,
                "ADMIN should get 204 NO CONTENT for DELETE /api/vulnerability-exceptions/{id}")
        } catch (e: HttpClientResponseException) {
            // If not found, should be 404, NOT 403
            assertEquals(HttpStatus.NOT_FOUND, e.status,
                "ADMIN should get 404 NOT FOUND (not 403 FORBIDDEN) for DELETE with non-existent ID")
        }
    }

    // ============================================================================
    // Test: VULN role can access all vulnerability endpoints
    // ============================================================================

    @Test
    fun `testVulnRoleCanAccessVulnEndpoints - should return 200 for all endpoints with VULN role`() {
        // Arrange
        val vulnToken = authenticateAsVuln()

        // Test 1: GET /api/vulnerabilities/current
        val getCurrentRequest = HttpRequest.GET<Any>("/api/vulnerabilities/current")
            .bearerAuth(vulnToken)

        val response1 = client.toBlocking().exchange(getCurrentRequest, String::class.java)
        assertEquals(HttpStatus.OK, response1.status,
            "VULN role should get 200 OK for GET /api/vulnerabilities/current")

        // Test 2: GET /api/vulnerability-exceptions
        val getExceptionsRequest = HttpRequest.GET<Any>("/api/vulnerability-exceptions")
            .bearerAuth(vulnToken)

        val response2 = client.toBlocking().exchange(getExceptionsRequest, String::class.java)
        assertEquals(HttpStatus.OK, response2.status,
            "VULN role should get 200 OK for GET /api/vulnerability-exceptions")

        // Test 3: POST /api/vulnerability-exceptions
        val createExceptionBody = mapOf(
            "exceptionType" to "PRODUCT",
            "targetValue" to "Apache 2.4.0",
            "reason" to "Test reason for vuln user"
        )
        val postExceptionRequest = HttpRequest.POST("/api/vulnerability-exceptions", createExceptionBody)
            .bearerAuth(vulnToken)

        val response3 = client.toBlocking().exchange(postExceptionRequest, String::class.java)
        assertEquals(HttpStatus.CREATED, response3.status,
            "VULN role should get 201 CREATED for POST /api/vulnerability-exceptions")

        // Test 4: PUT /api/vulnerability-exceptions/{id}
        val updateExceptionBody = mapOf(
            "exceptionType" to "IP",
            "targetValue" to "10.0.0.5",
            "reason" to "Updated reason by vuln user"
        )
        val putExceptionRequest = HttpRequest.PUT("/api/vulnerability-exceptions/99999", updateExceptionBody)
            .bearerAuth(vulnToken)

        try {
            val response4 = client.toBlocking().exchange(putExceptionRequest, String::class.java)
            // If successful, should be 200 OK
            assertEquals(HttpStatus.OK, response4.status,
                "VULN role should get 200 OK for PUT /api/vulnerability-exceptions/{id}")
        } catch (e: HttpClientResponseException) {
            // If not found, should be 404, NOT 403
            assertEquals(HttpStatus.NOT_FOUND, e.status,
                "VULN role should get 404 NOT FOUND (not 403 FORBIDDEN) for PUT with non-existent ID")
        }

        // Test 5: DELETE /api/vulnerability-exceptions/{id}
        val deleteExceptionRequest = HttpRequest.DELETE<Any>("/api/vulnerability-exceptions/99999")
            .bearerAuth(vulnToken)

        try {
            val response5 = client.toBlocking().exchange(deleteExceptionRequest, String::class.java)
            // If successful, should be 204 NO CONTENT
            assertEquals(HttpStatus.NO_CONTENT, response5.status,
                "VULN role should get 204 NO CONTENT for DELETE /api/vulnerability-exceptions/{id}")
        } catch (e: HttpClientResponseException) {
            // If not found, should be 404, NOT 403
            assertEquals(HttpStatus.NOT_FOUND, e.status,
                "VULN role should get 404 NOT FOUND (not 403 FORBIDDEN) for DELETE with non-existent ID")
        }
    }

    // ============================================================================
    // Test: Unauthenticated users cannot access vulnerability endpoints
    // ============================================================================

    @Test
    fun `testUnauthenticatedUserCannotAccessVulnEndpoints - should return 401 for all endpoints without JWT`() {
        // Test 1: GET /api/vulnerabilities/current
        val getCurrentRequest = HttpRequest.GET<Any>("/api/vulnerabilities/current")

        val exception1 = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(getCurrentRequest, String::class.java)
        }
        assertEquals(HttpStatus.UNAUTHORIZED, exception1.status,
            "Unauthenticated user should get 401 UNAUTHORIZED for GET /api/vulnerabilities/current")

        // Test 2: GET /api/vulnerability-exceptions
        val getExceptionsRequest = HttpRequest.GET<Any>("/api/vulnerability-exceptions")

        val exception2 = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(getExceptionsRequest, String::class.java)
        }
        assertEquals(HttpStatus.UNAUTHORIZED, exception2.status,
            "Unauthenticated user should get 401 UNAUTHORIZED for GET /api/vulnerability-exceptions")

        // Test 3: POST /api/vulnerability-exceptions
        val createExceptionBody = mapOf(
            "exceptionType" to "PRODUCT",
            "targetValue" to "OpenSSH 7.4",
            "reason" to "Test reason"
        )
        val postExceptionRequest = HttpRequest.POST("/api/vulnerability-exceptions", createExceptionBody)

        val exception3 = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(postExceptionRequest, String::class.java)
        }
        assertEquals(HttpStatus.UNAUTHORIZED, exception3.status,
            "Unauthenticated user should get 401 UNAUTHORIZED for POST /api/vulnerability-exceptions")

        // Test 4: PUT /api/vulnerability-exceptions/{id}
        val updateExceptionBody = mapOf(
            "exceptionType" to "IP",
            "targetValue" to "192.168.1.10",
            "reason" to "Updated reason"
        )
        val putExceptionRequest = HttpRequest.PUT("/api/vulnerability-exceptions/1", updateExceptionBody)

        val exception4 = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(putExceptionRequest, String::class.java)
        }
        assertEquals(HttpStatus.UNAUTHORIZED, exception4.status,
            "Unauthenticated user should get 401 UNAUTHORIZED for PUT /api/vulnerability-exceptions/{id}")

        // Test 5: DELETE /api/vulnerability-exceptions/{id}
        val deleteExceptionRequest = HttpRequest.DELETE<Any>("/api/vulnerability-exceptions/1")

        val exception5 = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(deleteExceptionRequest, String::class.java)
        }
        assertEquals(HttpStatus.UNAUTHORIZED, exception5.status,
            "Unauthenticated user should get 401 UNAUTHORIZED for DELETE /api/vulnerability-exceptions/{id}")
    }

    // ============================================================================
    // Test: Verify role precision - only ADMIN and VULN, not USER
    // ============================================================================

    @Test
    fun `testOnlyAdminAndVulnRolesGrantAccess - should verify precise role checking`() {
        // Test with ADMIN role
        val adminToken = authenticateAsAdmin()
        val adminRequest = HttpRequest.GET<Any>("/api/vulnerabilities/current")
            .bearerAuth(adminToken)
        val adminResponse = client.toBlocking().exchange(adminRequest, String::class.java)
        assertEquals(HttpStatus.OK, adminResponse.status,
            "ADMIN role should have access to vulnerability endpoints")

        // Test with VULN role
        val vulnToken = authenticateAsVuln()
        val vulnRequest = HttpRequest.GET<Any>("/api/vulnerabilities/current")
            .bearerAuth(vulnToken)
        val vulnResponse = client.toBlocking().exchange(vulnRequest, String::class.java)
        assertEquals(HttpStatus.OK, vulnResponse.status,
            "VULN role should have access to vulnerability endpoints")

        // Test with USER role (should fail)
        val userToken = authenticateAsUser()
        val userRequest = HttpRequest.GET<Any>("/api/vulnerabilities/current")
            .bearerAuth(userToken)
        val userException = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(userRequest, String::class.java)
        }
        assertEquals(HttpStatus.FORBIDDEN, userException.status,
            "USER role should NOT have access to vulnerability endpoints")
    }

    // ============================================================================
    // Test: Verify exception filtering based on role
    // ============================================================================

    @Test
    fun `testExceptionFilteringRespectsSeverity - should apply filters correctly for VULN role`() {
        // Arrange
        val vulnToken = authenticateAsVuln()

        // Test severity filter
        val severityRequest = HttpRequest.GET<Any>("/api/vulnerabilities/current?severity=High")
            .bearerAuth(vulnToken)
        val severityResponse = client.toBlocking().exchange(severityRequest, String::class.java)
        assertEquals(HttpStatus.OK, severityResponse.status,
            "VULN role should be able to use severity filter")

        // Test system filter
        val systemRequest = HttpRequest.GET<Any>("/api/vulnerabilities/current?system=web-server-01")
            .bearerAuth(vulnToken)
        val systemResponse = client.toBlocking().exchange(systemRequest, String::class.java)
        assertEquals(HttpStatus.OK, systemResponse.status,
            "VULN role should be able to use system filter")

        // Test exceptionStatus filter
        val exceptionRequest = HttpRequest.GET<Any>("/api/vulnerabilities/current?exceptionStatus=excepted")
            .bearerAuth(vulnToken)
        val exceptionResponse = client.toBlocking().exchange(exceptionRequest, String::class.java)
        assertEquals(HttpStatus.OK, exceptionResponse.status,
            "VULN role should be able to use exceptionStatus filter")

        // Test combined filters
        val combinedRequest = HttpRequest.GET<Any>("/api/vulnerabilities/current?severity=High&exceptionStatus=not-excepted")
            .bearerAuth(vulnToken)
        val combinedResponse = client.toBlocking().exchange(combinedRequest, String::class.java)
        assertEquals(HttpStatus.OK, combinedResponse.status,
            "VULN role should be able to use multiple filters")
    }

    // ============================================================================
    // Test: Verify activeOnly filter for exceptions
    // ============================================================================

    @Test
    fun `testExceptionActiveOnlyFilter - should filter exceptions correctly for VULN role`() {
        // Arrange
        val vulnToken = authenticateAsVuln()

        // Test without filter (should return all)
        val allRequest = HttpRequest.GET<Any>("/api/vulnerability-exceptions")
            .bearerAuth(vulnToken)
        val allResponse = client.toBlocking().exchange(allRequest, String::class.java)
        assertEquals(HttpStatus.OK, allResponse.status,
            "VULN role should be able to get all exceptions")

        // Test with activeOnly=true filter
        val activeRequest = HttpRequest.GET<Any>("/api/vulnerability-exceptions?activeOnly=true")
            .bearerAuth(vulnToken)
        val activeResponse = client.toBlocking().exchange(activeRequest, String::class.java)
        assertEquals(HttpStatus.OK, activeResponse.status,
            "VULN role should be able to filter active exceptions")

        // Test with type filter
        val typeRequest = HttpRequest.GET<Any>("/api/vulnerability-exceptions?type=IP")
            .bearerAuth(vulnToken)
        val typeResponse = client.toBlocking().exchange(typeRequest, String::class.java)
        assertEquals(HttpStatus.OK, typeResponse.status,
            "VULN role should be able to filter exceptions by type")
    }

    // ============================================================================
    // Test: Verify token expiration handling
    // ============================================================================

    @Test
    fun `testExpiredTokenRejected - should return 401 for expired or invalid token`() {
        // Arrange - Create invalid/expired token
        val expiredToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.expired.token"

        // Act & Assert
        val request = HttpRequest.GET<Any>("/api/vulnerabilities/current")
            .bearerAuth(expiredToken)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status,
            "Expired/invalid token should get 401 UNAUTHORIZED")
    }

    // ============================================================================
    // Helper methods for authentication
    // ============================================================================

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

    private fun authenticateAsVuln(): String {
        val credentials = UsernamePasswordCredentials("vuln_user", "vuln_user")
        val request = HttpRequest.POST("/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()!!["access_token"] as String
    }
}
