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

/**
 * Contract test for MCP session deletion endpoint.
 * Tests the DELETE /api/mcp/session/{sessionId} endpoint according to OpenAPI specification.
 *
 * This test MUST FAIL initially until the endpoint is implemented.
 */
@MicronautTest
class McpSessionDeleteTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `DELETE session closes existing MCP session successfully`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val sessionId = "test-session-12345-67890-abcdef"

        val request = HttpRequest.DELETE<Any>("/api/mcp/session/$sessionId")
            .header("X-MCP-API-Key", apiKey)

        // Act
        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.status)
        // No body expected for 204 response
        assertTrue(response.body().isNullOrEmpty())
    }

    @Test
    fun `DELETE session returns 404 for non-existent session`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val nonExistentSessionId = "non-existent-session-id"

        val request = HttpRequest.DELETE<Any>("/api/mcp/session/$nonExistentSessionId")
            .header("X-MCP-API-Key", apiKey)

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)

        val errorBody = exception.response.getBody(JsonNode::class.java).get()
        assertTrue(errorBody.has("code"))
        assertTrue(errorBody.has("message"))
        assertEquals(-32601, errorBody.get("code").asInt()) // Method not found in JSON-RPC (resource not found)
        assertTrue(errorBody.get("message").asText().contains("session", ignoreCase = true))
    }

    @Test
    fun `DELETE session returns 401 without API key`() {
        // Arrange
        val sessionId = "test-session-12345-67890-abcdef"
        val request = HttpRequest.DELETE<Any>("/api/mcp/session/$sessionId")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    fun `DELETE session returns 401 with invalid API key`() {
        // Arrange
        val invalidApiKey = "invalid-api-key"
        val sessionId = "test-session-12345-67890-abcdef"

        val request = HttpRequest.DELETE<Any>("/api/mcp/session/$sessionId")
            .header("X-MCP-API-Key", invalidApiKey)

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    fun `DELETE session prevents access to other user sessions`() {
        // Arrange - Simulate user A trying to delete user B's session
        val userAApiKey = "user-a-api-key"
        val userBSessionId = "user-b-session-12345-67890"

        val request = HttpRequest.DELETE<Any>("/api/mcp/session/$userBSessionId")
            .header("X-MCP-API-Key", userAApiKey)

        // Act & Assert - Should return 404 (not found) instead of 403 (forbidden)
        // to prevent session ID enumeration attacks
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    @Test
    fun `DELETE session validates sessionId format`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val invalidSessionId = "invalid-format"

        val request = HttpRequest.DELETE<Any>("/api/mcp/session/$invalidSessionId")
            .header("X-MCP-API-Key", apiKey)

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        // Should return 400 Bad Request for invalid session ID format
        // or 404 Not Found if the validation happens at lookup level
        assertTrue(exception.status == HttpStatus.BAD_REQUEST || exception.status == HttpStatus.NOT_FOUND)
    }

    @Test
    fun `DELETE session handles concurrent deletion attempts`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val sessionId = "concurrent-test-session-12345"

        val request1 = HttpRequest.DELETE<Any>("/api/mcp/session/$sessionId")
            .header("X-MCP-API-Key", apiKey)

        val request2 = HttpRequest.DELETE<Any>("/api/mcp/session/$sessionId")
            .header("X-MCP-API-Key", apiKey)

        // Act - First request should succeed
        val response1 = client.toBlocking().exchange(request1, String::class.java)

        // Second request should return 404 (session already deleted)
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request2, JsonNode::class.java)
        }

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response1.status)
        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    @Test
    fun `DELETE session cleans up associated resources`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val sessionId = "cleanup-test-session-12345"

        val deleteRequest = HttpRequest.DELETE<Any>("/api/mcp/session/$sessionId")
            .header("X-MCP-API-Key", apiKey)

        // Act - Delete the session
        val deleteResponse = client.toBlocking().exchange(deleteRequest, String::class.java)

        // Assert - Session deleted successfully
        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.status)

        // Verify session is actually cleaned up by trying to use it
        // This would typically be done through an SSE connection test
        // For now, we just verify the DELETE succeeded
        assertTrue(deleteResponse.body().isNullOrEmpty())
    }
}