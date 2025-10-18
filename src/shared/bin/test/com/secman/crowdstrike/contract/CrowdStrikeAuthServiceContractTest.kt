package com.secman.crowdstrike.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.crowdstrike.auth.CrowdStrikeAuthService
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.exception.AuthenticationException
import com.secman.crowdstrike.exception.RateLimitException
import com.secman.crowdstrike.model.AuthToken
import io.micronaut.http.client.HttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract tests for CrowdStrikeAuthService
 *
 * Tests OAuth2 authentication flow with MockWebServer
 * TDD approach: Tests written first, then implementation
 *
 * Related to: Feature 023-create-in-the
 * Tasks: T034-T036
 */
class CrowdStrikeAuthServiceContractTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var authService: CrowdStrikeAuthService
    private lateinit var httpClient: HttpClient
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        // Create HTTP client pointing to mock server
        httpClient = HttpClient.create(mockServer.url("").toUrl())
        authService = CrowdStrikeAuthService(httpClient)
    }

    @AfterEach
    fun teardown() {
        mockServer.shutdown()
        httpClient.close()
    }

    /**
     * Task: T034
     * Contract test: OAuth2 authentication success
     *
     * Given valid credentials
     * When authenticate() is called
     * Then should return valid AuthToken with access_token and expiration
     */
    @Test
    fun `authenticate should return AuthToken on successful OAuth2 response`() {
        // Arrange
        val config = FalconConfigDto(
            clientId = "test-client-id",
            clientSecret = "test-client-secret",
            baseUrl = mockServer.url("").toString()
        )

        val responseBody = mapOf(
            "access_token" to "valid-access-token-123",
            "token_type" to "bearer",
            "expires_in" to 1800
        )

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(responseBody))
        )

        // Act
        val token = authService.authenticate(config)

        // Assert
        assertNotNull(token)
        assertEquals("valid-access-token-123", token.accessToken)
        assertEquals("bearer", token.tokenType)
        assertTrue(token.expiresAt.isAfter(Instant.now()))

        // Verify request
        val request = mockServer.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path?.contains("/oauth2/token") == true)
    }

    /**
     * Task: T035
     * Contract test: OAuth2 authentication failure with 401 (unauthorized)
     *
     * Given invalid credentials
     * When authenticate() is called
     * Then should throw AuthenticationException
     */
    @Test
    fun `authenticate should throw AuthenticationException on 401 response`() {
        // Arrange
        val config = FalconConfigDto(
            clientId = "invalid-client-id",
            clientSecret = "invalid-client-secret",
            baseUrl = mockServer.url("").toString()
        )

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"error": "invalid_client"}""")
        )

        // Act & Assert
        val exception = assertThrows<AuthenticationException> {
            authService.authenticate(config)
        }

        assertTrue(exception.message?.contains("401") == true || exception.message?.contains("authentication") == true)
    }

    /**
     * Task: T035 (extended)
     * Contract test: OAuth2 authentication failure with 403 (forbidden)
     *
     * Given credentials with insufficient permissions
     * When authenticate() is called
     * Then should throw AuthenticationException
     */
    @Test
    fun `authenticate should throw AuthenticationException on 403 response`() {
        // Arrange
        val config = FalconConfigDto(
            clientId = "restricted-client-id",
            clientSecret = "restricted-client-secret",
            baseUrl = mockServer.url("").toString()
        )

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"error": "insufficient_scope"}""")
        )

        // Act & Assert
        val exception = assertThrows<AuthenticationException> {
            authService.authenticate(config)
        }

        assertTrue(exception.message?.contains("403") == true || exception.message?.contains("authentication") == true)
    }

    /**
     * Task: T036
     * Contract test: OAuth2 rate limit retry with 429
     *
     * Given rate limit exceeded (429)
     * When authenticate() is called
     * Then should throw RateLimitException with retry-after info
     */
    @Test
    fun `authenticate should throw RateLimitException on 429 response`() {
        // Arrange
        val config = FalconConfigDto(
            clientId = "test-client-id",
            clientSecret = "test-client-secret",
            baseUrl = mockServer.url("").toString()
        )

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "60")
                .addHeader("Content-Type", "application/json")
                .setBody("""{"error": "too_many_requests"}""")
        )

        // Act & Assert
        val exception = assertThrows<RateLimitException> {
            authService.authenticate(config)
        }

        assertEquals(60L, exception.retryAfterSeconds)
    }

    /**
     * Contract test: Token caching
     *
     * Given successful authentication
     * When authenticate() is called again within token validity
     * Then should return cached token without making new HTTP request
     */
    @Test
    fun `authenticate should return cached token on subsequent calls`() {
        // Arrange
        val config = FalconConfigDto(
            clientId = "test-client-id",
            clientSecret = "test-client-secret",
            baseUrl = mockServer.url("").toString()
        )

        val responseBody = mapOf(
            "access_token" to "cached-token-123",
            "token_type" to "bearer",
            "expires_in" to 1800
        )

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(responseBody))
        )

        // Act - First call
        val token1 = authService.authenticate(config)

        // Assert - First call made HTTP request
        assertEquals(1, mockServer.requestCount)

        // Act - Second call (should use cache)
        val token2 = authService.authenticate(config)

        // Assert - Second call should NOT make new HTTP request
        assertEquals(1, mockServer.requestCount)
        assertEquals(token1.accessToken, token2.accessToken)
    }

    /**
     * Contract test: Cache clear functionality
     *
     * Given cached token
     * When clearCache() is called
     * Then subsequent authenticate() should make new HTTP request
     */
    @Test
    fun `clearCache should clear cached token`() {
        // Arrange
        val config = FalconConfigDto(
            clientId = "test-client-id",
            clientSecret = "test-client-secret",
            baseUrl = mockServer.url("").toString()
        )

        val responseBody = mapOf(
            "access_token" to "token-123",
            "token_type" to "bearer",
            "expires_in" to 1800
        )

        // First request
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(responseBody))
        )

        // Second request (after cache clear)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(responseBody))
        )

        // Act
        authService.authenticate(config)
        assertEquals(1, mockServer.requestCount)

        authService.clearCache()
        authService.authenticate(config)

        // Assert - Should have made two requests
        assertEquals(2, mockServer.requestCount)
    }
}
