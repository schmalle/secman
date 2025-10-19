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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Contract tests for GET /api/users/profile endpoint
 * Feature 028: User Profile Page
 *
 * Tests verify:
 * - T006: 200 success case with authenticated request
 * - T007: 401 unauthorized case without authentication
 * - T008: 404 not found case when user doesn't exist
 * - T009: passwordHash excluded from response
 */
@MicronautTest
class UserProfileContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    private lateinit var testUser: User
    private lateinit var authToken: String

    @BeforeEach
    fun setup() {
        // Create test user
        testUser = User(
            username = "testuser",
            email = "testuser@example.com",
            passwordHash = "hashedPassword123",
            roles = mutableSetOf(User.Role.USER, User.Role.ADMIN)
        )
        testUser = userRepository.save(testUser)

        // Get auth token
        val credentials = UsernamePasswordCredentials("testuser", "testpassword")
        val loginRequest = HttpRequest.POST("/api/auth/login", credentials)

        try {
            val loginResponse = client.toBlocking().retrieve(loginRequest, Map::class.java)
            authToken = loginResponse["access_token"] as String
        } catch (e: Exception) {
            // If login fails, use a mock token for unauthenticated tests
            authToken = "invalid-token"
        }
    }

    @AfterEach
    fun cleanup() {
        try {
            userRepository.deleteById(testUser.id!!)
        } catch (e: Exception) {
            // User may not exist in some tests
        }
    }

    /**
     * T006: Test GET /api/users/profile returns 200 with valid user data when authenticated
     */
    @Test
    fun `GET profile returns 200 with valid user data when authenticated`() {
        // Given: authenticated user
        val request = HttpRequest.GET<Any>("/api/users/profile")
            .bearerAuth(authToken)

        // When: requesting profile
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Then: expect 200 OK with user data
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body()
        assertNotNull(body)
        assertEquals("testuser", body!!["username"])
        assertEquals("testuser@example.com", body["email"])

        @Suppress("UNCHECKED_CAST")
        val roles = body["roles"] as List<String>
        assertTrue(roles.contains("USER"))
        assertTrue(roles.contains("ADMIN"))
    }

    /**
     * T007: Test GET /api/users/profile returns 401 when not authenticated
     */
    @Test
    fun `GET profile returns 401 when not authenticated`() {
        // Given: no authentication
        val request = HttpRequest.GET<Any>("/api/users/profile")

        // When: requesting profile without auth
        // Then: expect 401 Unauthorized
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, Map::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    /**
     * T008: Test GET /api/users/profile returns 404 when user not found
     * Note: This test simulates a case where user is deleted after login
     */
    @Test
    fun `GET profile returns 404 when user not found in database`() {
        // Given: authenticated user that was deleted from database
        val request = HttpRequest.GET<Any>("/api/users/profile")
            .bearerAuth(authToken)

        // Delete user from database
        userRepository.deleteById(testUser.id!!)

        // When: requesting profile
        // Then: expect 404 Not Found
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, Map::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    /**
     * T009: Test that passwordHash is excluded from response
     */
    @Test
    fun `GET profile response excludes sensitive fields`() {
        // Given: authenticated user
        val request = HttpRequest.GET<Any>("/api/users/profile")
            .bearerAuth(authToken)

        // When: requesting profile
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Then: response should not contain passwordHash
        val body = response.body()
        assertNotNull(body)
        assertFalse(body!!.containsKey("passwordHash"))
        assertFalse(body.containsKey("password"))
        assertFalse(body.containsKey("passwordhash"))

        // Also verify id, createdAt, updatedAt are excluded
        assertFalse(body.containsKey("id"))
        assertFalse(body.containsKey("createdAt"))
        assertFalse(body.containsKey("updatedAt"))
    }
}
