package com.secman.contract

import com.secman.domain.User
import com.secman.repository.UserRepository
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.security.authentication.UsernamePasswordCredentials
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/**
 * Contract test for POST /api/workgroups endpoint
 * Feature: 008-create-an-additional (Workgroup-Based Access Control)
 *
 * Tests the workgroup creation endpoint per contract: workgroup-crud.yaml
 * Expected to FAIL until WorkgroupController is implemented
 */
@MicronautTest
class WorkgroupCreateContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    private val passwordEncoder = BCryptPasswordEncoder()
    private lateinit var adminToken: String

    @BeforeEach
    fun setup() {
        // Create admin user for testing
        val adminUser = User(
            username = "admin_test",
            email = "admin@test.com",
            passwordHash = passwordEncoder.encode("admin123"),
            roles = mutableSetOf(User.Role.ADMIN)
        )
        userRepository.save(adminUser)

        // Get JWT token for admin
        val credentials = UsernamePasswordCredentials("admin_test", "admin123")
        val loginRequest = HttpRequest.POST("/api/auth/login", credentials)
        val loginResponse = client.toBlocking().exchange(loginRequest, Map::class.java)
        adminToken = (loginResponse.body() as Map<*, *>)["token"] as String
    }

    @Test
    fun `test creates workgroup with valid name`() {
        // Given: A request to create a workgroup with valid data
        val request = mapOf(
            "name" to "Engineering Team",
            "description" to "All engineers and developers"
        )

        // When: POST /api/workgroups
        val httpRequest = HttpRequest.POST("/api/workgroups", request)
            .bearerAuth(adminToken)

        // Then: Expect 201 Created with workgroup response
        // EXPECTED TO FAIL: WorkgroupController not implemented yet
        val response = client.toBlocking().exchange(httpRequest, Map::class.java)

        assertEquals(HttpStatus.CREATED, response.status)
        val body = response.body() as Map<*, *>
        assertNotNull(body["id"])
        assertEquals("Engineering Team", body["name"])
        assertEquals("All engineers and developers", body["description"])
        assertNotNull(body["createdAt"])
        assertNotNull(body["updatedAt"])
    }

    @Test
    fun `test rejects duplicate name (case-insensitive)`() {
        // Given: A workgroup "DevOps" already exists
        val firstRequest = mapOf("name" to "DevOps", "description" to "DevOps team")
        client.toBlocking().exchange(
            HttpRequest.POST("/api/workgroups", firstRequest).bearerAuth(adminToken),
            Map::class.java
        )

        // When: Attempting to create "devops" (different case)
        val duplicateRequest = mapOf("name" to "devops")
        val httpRequest = HttpRequest.POST("/api/workgroups", duplicateRequest)
            .bearerAuth(adminToken)

        // Then: Expect 400 Bad Request with error message
        // EXPECTED TO FAIL: WorkgroupService validation not implemented yet
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, Map::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        val errorBody = exception.response.getBody(Map::class.java).get()
        assertTrue((errorBody["error"] as String).contains("already exists", ignoreCase = true))
    }

    @Test
    fun `test validates name format (alphanumeric + spaces + hyphens)`() {
        // Given: A request with invalid characters in name
        val request = mapOf("name" to "DevOps@2025!")

        // When: POST /api/workgroups
        val httpRequest = HttpRequest.POST("/api/workgroups", request)
            .bearerAuth(adminToken)

        // Then: Expect 400 Bad Request
        // EXPECTED TO FAIL: Validation not implemented yet
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, Map::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        val errorBody = exception.response.getBody(Map::class.java).get()
        assertTrue((errorBody["error"] as String).contains("alphanumeric", ignoreCase = true))
    }

    @Test
    fun `test requires ADMIN role`() {
        // Given: A regular user (non-admin)
        val regularUser = User(
            username = "regular_user",
            email = "user@test.com",
            passwordHash = passwordEncoder.encode("user123"),
            roles = mutableSetOf(User.Role.USER)
        )
        userRepository.save(regularUser)

        val credentials = UsernamePasswordCredentials("regular_user", "user123")
        val loginResponse = client.toBlocking().exchange(
            HttpRequest.POST("/api/auth/login", credentials),
            Map::class.java
        )
        val userToken = (loginResponse.body() as Map<*, *>)["token"] as String

        // When: Regular user attempts to create workgroup
        val request = mapOf("name" to "Engineering")
        val httpRequest = HttpRequest.POST("/api/workgroups", request)
            .bearerAuth(userToken)

        // Then: Expect 403 Forbidden
        // EXPECTED TO FAIL: @Secured("ADMIN") not implemented yet
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, Map::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    fun `test requires authentication`() {
        // Given: No authentication token
        val request = mapOf("name" to "Engineering")

        // When: POST /api/workgroups without token
        val httpRequest = HttpRequest.POST("/api/workgroups", request)

        // Then: Expect 401 Unauthorized
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, Map::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }
}
