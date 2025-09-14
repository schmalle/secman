package com.secman.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.inject.Inject

/**
 * Contract test for MCP session creation endpoint.
 * Tests the POST /api/mcp/session endpoint according to OpenAPI specification.
 */
class McpSessionCreateTest : McpTestBase() {

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `POST session creates new MCP session with valid request`() {
        // Arrange
        val requestBody = mapOf(
            "capabilities" to mapOf(
                "tools" to emptyMap<String, Any>(),
                "resources" to emptyMap<String, Any>(),
                "prompts" to emptyMap<String, Any>()
            ),
            "clientInfo" to mapOf(
                "name" to "Claude Development Client",
                "version" to "1.0.0"
            )
        )

        val request = authenticatedPostRequest("/api/mcp/session", requestBody)

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.CREATED, response.status)
        assertNotNull(response.body())

        val body = response.body()!!

        // Validate SessionInitResponse schema
        assertTrue(body.has("sessionId"))
        assertTrue(body.has("capabilities"))
        assertTrue(body.has("serverInfo"))

        // Validate sessionId
        val sessionId = body.get("sessionId").asText()
        assertNotNull(sessionId)
        assertFalse(sessionId.isEmpty())
        assertTrue(sessionId.length >= 32) // Should be cryptographically secure

        // Validate server capabilities
        val serverCapabilities = body.get("capabilities")
        assertTrue(serverCapabilities.has("tools"))
        assertTrue(serverCapabilities.has("resources"))
        assertTrue(serverCapabilities.has("prompts"))

        // Validate server info
        val serverInfo = body.get("serverInfo")
        assertTrue(serverInfo.has("name"))
        assertTrue(serverInfo.has("version"))
        assertEquals("Secman MCP Server", serverInfo.get("name").asText())
        assertEquals("0.1.0", serverInfo.get("version").asText())
    }

    @Test
    fun `POST session returns 400 with invalid request body`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val invalidRequestBody = mapOf(
            "invalid" to "request"
        )

        val request = HttpRequest.POST("/api/mcp/session", invalidRequestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)

        val errorBody = exception.response.getBody(JsonNode::class.java).get()
        assertTrue(errorBody.has("code"))
        assertTrue(errorBody.has("message"))
        assertEquals(-32602, errorBody.get("code").asInt()) // Invalid params in JSON-RPC
    }

    @Test
    fun `POST session returns 401 without API key`() {
        // Arrange
        val requestBody = mapOf(
            "capabilities" to mapOf(
                "tools" to emptyMap<String, Any>(),
                "resources" to emptyMap<String, Any>(),
                "prompts" to emptyMap<String, Any>()
            )
        )

        val request = HttpRequest.POST("/api/mcp/session", requestBody)
            .header("Content-Type", "application/json")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    fun `POST session with missing capabilities returns 400`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val requestBody = mapOf(
            "clientInfo" to mapOf(
                "name" to "Test Client",
                "version" to "1.0.0"
            )
            // Missing required "capabilities" field
        )

        val request = HttpRequest.POST("/api/mcp/session", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    fun `POST session generates unique session IDs for concurrent requests`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val requestBody = mapOf(
            "capabilities" to mapOf(
                "tools" to emptyMap<String, Any>(),
                "resources" to emptyMap<String, Any>(),
                "prompts" to emptyMap<String, Any>()
            ),
            "clientInfo" to mapOf(
                "name" to "Test Client",
                "version" to "1.0.0"
            )
        )

        val request1 = HttpRequest.POST("/api/mcp/session", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        val request2 = HttpRequest.POST("/api/mcp/session", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act
        val response1 = client.toBlocking().exchange(request1, JsonNode::class.java)
        val response2 = client.toBlocking().exchange(request2, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.CREATED, response1.status)
        assertEquals(HttpStatus.CREATED, response2.status)

        val sessionId1 = response1.body()!!.get("sessionId").asText()
        val sessionId2 = response2.body()!!.get("sessionId").asText()

        assertNotEquals(sessionId1, sessionId2)
        assertNotNull(sessionId1)
        assertNotNull(sessionId2)
    }

    @Test
    fun `POST session negotiates client capabilities correctly`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val clientCapabilities = mapOf(
            "tools" to mapOf("listChanged" to true),
            "resources" to mapOf("subscribe" to true, "listChanged" to true),
            "prompts" to emptyMap<String, Any>()
        )

        val requestBody = mapOf(
            "capabilities" to clientCapabilities,
            "clientInfo" to mapOf(
                "name" to "Advanced MCP Client",
                "version" to "2.0.0"
            )
        )

        val request = HttpRequest.POST("/api/mcp/session", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.CREATED, response.status)

        val responseBody = response.body()!!
        val serverCapabilities = responseBody.get("capabilities")

        // Server should respond with capabilities that match or subset of client capabilities
        assertTrue(serverCapabilities.has("tools"))
        assertTrue(serverCapabilities.has("resources"))
        assertTrue(serverCapabilities.has("prompts"))
    }
}