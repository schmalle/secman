package com.secman.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Contract test for MCP Server-Sent Events endpoint.
 * Tests the GET /api/mcp/sse/{sessionId} endpoint according to OpenAPI specification.
 *
 * This test MUST FAIL initially until the endpoint is implemented.
 */
@MicronautTest
class McpSseTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `GET SSE establishes event stream for valid session`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val sessionId = "valid-session-12345-67890-abcdef"

        val request = HttpRequest.GET<String>("/api/mcp/sse/$sessionId")
            .header("X-MCP-API-Key", apiKey)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")

        // Act
        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        // Validate SSE headers
        val contentType = response.headers.get("Content-Type")
        assertNotNull(contentType)
        assertTrue(contentType!!.contains("text/event-stream"))

        val cacheControl = response.headers.get("Cache-Control")
        assertNotNull(cacheControl)
        assertTrue(cacheControl!!.contains("no-cache"))

        // Initial SSE connection should send initialization event
        val body = response.body()
        assertNotNull(body)
        assertTrue(body!!.contains("event: mcp-message"))
        assertTrue(body.contains("data: "))
    }

    @Test
    fun `GET SSE returns 404 for non-existent session`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val nonExistentSessionId = "non-existent-session-id"

        val request = HttpRequest.GET<String>("/api/mcp/sse/$nonExistentSessionId")
            .header("X-MCP-API-Key", apiKey)
            .header("Accept", "text/event-stream")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    @Test
    fun `GET SSE returns 401 without API key`() {
        // Arrange
        val sessionId = "test-session-12345-67890-abcdef"
        val request = HttpRequest.GET<String>("/api/mcp/sse/$sessionId")
            .header("Accept", "text/event-stream")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    fun `GET SSE prevents cross-user session access`() {
        // Arrange - User A trying to access User B's session
        val userAApiKey = "user-a-api-key"
        val userBSessionId = "user-b-session-12345-67890"

        val request = HttpRequest.GET<String>("/api/mcp/sse/$userBSessionId")
            .header("X-MCP-API-Key", userAApiKey)
            .header("Accept", "text/event-stream")

        // Act & Assert - Should return 404 to prevent session enumeration
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    @Test
    fun `GET SSE sends initialization message on connect`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val sessionId = "initialized-session-12345"

        val request = HttpRequest.GET<String>("/api/mcp/sse/$sessionId")
            .header("X-MCP-API-Key", apiKey)
            .header("Accept", "text/event-stream")

        // Act
        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body()!!

        // Should contain initialization event
        assertTrue(body.contains("event: mcp-message"))
        assertTrue(body.contains("data: {"))

        // Parse the data to validate JSON-RPC structure
        val lines = body.split("\n")
        val dataLine = lines.find { it.startsWith("data: ") }
        assertNotNull(dataLine)

        val jsonData = dataLine!!.substring(6) // Remove "data: " prefix

        // Should be valid JSON with jsonrpc structure
        assertTrue(jsonData.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(jsonData.contains("\"method\":\"notifications/initialized\""))
    }

    @Test
    fun `GET SSE handles session expiration gracefully`() {
        // Arrange - Session that will expire during connection
        val apiKey = "test-api-key-12345"
        val expiringSessionId = "expiring-session-12345"

        val request = HttpRequest.GET<String>("/api/mcp/sse/$expiringSessionId")
            .header("X-MCP-API-Key", apiKey)
            .header("Accept", "text/event-stream")

        // Act - Initial connection should work
        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        // Session expiration should be handled by the server
        // The connection might close gracefully or send a final event
        val body = response.body()!!
        assertNotNull(body)
    }

    @Test
    fun `GET SSE validates session ownership`() {
        // Arrange - Valid API key but session belongs to different user
        val apiKey = "valid-api-key-12345"
        val otherUserSessionId = "other-user-session-67890"

        val request = HttpRequest.GET<String>("/api/mcp/sse/$otherUserSessionId")
            .header("X-MCP-API-Key", apiKey)
            .header("Accept", "text/event-stream")

        // Act & Assert - Should return 404, not 403, to prevent session enumeration
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    @Test
    fun `GET SSE handles concurrent connections to same session`() {
        // Arrange - Multiple connections to the same session
        val apiKey = "test-api-key-12345"
        val sessionId = "concurrent-session-12345"

        val request1 = HttpRequest.GET<String>("/api/mcp/sse/$sessionId")
            .header("X-MCP-API-Key", apiKey)
            .header("Accept", "text/event-stream")

        val request2 = HttpRequest.GET<String>("/api/mcp/sse/$sessionId")
            .header("X-MCP-API-Key", apiKey)
            .header("Accept", "text/event-stream")

        // Act - Both connections should succeed (or server should handle gracefully)
        val response1 = client.toBlocking().exchange(request1, String::class.java)

        // Second connection might succeed or be rejected depending on server policy
        try {
            val response2 = client.toBlocking().exchange(request2, String::class.java)

            // If both succeed, both should return 200
            assertEquals(HttpStatus.OK, response1.status)
            assertEquals(HttpStatus.OK, response2.status)
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            // If second connection is rejected, first should still be successful
            assertEquals(HttpStatus.OK, response1.status)
            assertTrue(e.status == HttpStatus.CONFLICT || e.status == HttpStatus.TOO_MANY_REQUESTS)
        }
    }

    @Test
    fun `GET SSE maintains connection for heartbeat`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val sessionId = "heartbeat-session-12345"

        val request = HttpRequest.GET<String>("/api/mcp/sse/$sessionId")
            .header("X-MCP-API-Key", apiKey)
            .header("Accept", "text/event-stream")

        // Act
        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body()!!

        // Connection should be established successfully
        assertTrue(body.contains("event: mcp-message"))

        // Should contain proper SSE formatting
        val lines = body.split("\n")
        assertTrue(lines.any { it.startsWith("event: ") })
        assertTrue(lines.any { it.startsWith("data: ") })
    }

    @Test
    fun `GET SSE includes proper CORS headers for cross-origin requests`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val sessionId = "cors-test-session-12345"

        val request = HttpRequest.GET<String>("/api/mcp/sse/$sessionId")
            .header("X-MCP-API-Key", apiKey)
            .header("Accept", "text/event-stream")
            .header("Origin", "http://localhost:4321")

        // Act
        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        // Should include CORS headers for SSE
        assertTrue(response.headers.contains("Access-Control-Allow-Origin"))
        assertTrue(response.headers.contains("Access-Control-Allow-Headers"))
    }
}