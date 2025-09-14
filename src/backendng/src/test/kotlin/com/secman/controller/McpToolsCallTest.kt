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
 * Contract test for MCP tool call endpoint.
 * Tests the POST /api/mcp/tools/call endpoint according to OpenAPI specification.
 *
 * This test MUST FAIL initially until the endpoint is implemented.
 */
@MicronautTest
class McpToolsCallTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `POST tools call executes get_requirements tool successfully`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "get-requirements-test",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to "get_requirements",
                "arguments" to mapOf(
                    "limit" to 5,
                    "status" to "ACTIVE"
                )
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        val body = response.body()!!

        // Validate JSON-RPC response structure
        assertTrue(body.has("jsonrpc"))
        assertTrue(body.has("id"))
        assertTrue(body.has("result"))
        assertEquals("2.0", body.get("jsonrpc").asText())
        assertEquals("get-requirements-test", body.get("id").asText())

        // Validate tool call result structure
        val result = body.get("result")
        assertTrue(result.has("content"))
        assertFalse(result.get("isError").asBoolean(false))

        val content = result.get("content")
        assertTrue(content.isArray)
        assertTrue(content.size() > 0)

        // Validate content structure
        val firstContent = content.get(0)
        assertTrue(firstContent.has("type"))
        assertTrue(firstContent.has("text"))
        assertEquals("text", firstContent.get("type").asText())

        // Parse the text content as JSON to validate requirements data
        val textContent = firstContent.get("text").asText()
        assertNotNull(textContent)
        assertTrue(textContent.isNotEmpty())
    }

    @Test
    fun `POST tools call executes create_requirement tool successfully`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "create-requirement-test",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to "create_requirement",
                "arguments" to mapOf(
                    "title" to "Test Security Requirement",
                    "description" to "This is a test requirement created via MCP API",
                    "category" to "Testing",
                    "priority" to "MEDIUM",
                    "tags" to listOf("test", "mcp", "api")
                )
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body()!!
        val result = body.get("result")

        assertTrue(result.has("content"))
        assertFalse(result.get("isError").asBoolean(false))

        val content = result.get("content")
        assertTrue(content.isArray)
        assertTrue(content.size() > 0)

        val firstContent = content.get(0)
        assertEquals("text", firstContent.get("type").asText())

        // Should contain created requirement data
        val textContent = firstContent.get("text").asText()
        assertTrue(textContent.contains("Test Security Requirement"))
    }

    @Test
    fun `POST tools call returns error for unknown tool`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "unknown-tool-test",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to "unknown_tool",
                "arguments" to emptyMap<String, Any>()
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status) // JSON-RPC errors still return 200

        val body = response.body()!!
        val result = body.get("result")

        assertTrue(result.has("content"))
        assertTrue(result.get("isError").asBoolean())

        val content = result.get("content")
        val errorContent = content.get(0)
        assertEquals("text", errorContent.get("type").asText())
        assertTrue(errorContent.get("text").asText().contains("Tool not found", ignoreCase = true))
    }

    @Test
    fun `POST tools call validates tool arguments`() {
        // Arrange - Invalid arguments for get_requirements
        val apiKey = "test-api-key-12345"
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "invalid-args-test",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to "get_requirements",
                "arguments" to mapOf(
                    "limit" to -1, // Invalid limit
                    "status" to "INVALID_STATUS" // Invalid enum value
                )
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body()!!
        val result = body.get("result")

        assertTrue(result.get("isError").asBoolean())

        val content = result.get("content")
        val errorContent = content.get(0)
        assertTrue(errorContent.get("text").asText().contains("validation", ignoreCase = true))
    }

    @Test
    fun `POST tools call enforces permission-based access`() {
        // Arrange - API key without REQUIREMENTS_WRITE permission trying to create requirement
        val limitedApiKey = "limited-api-key-no-write"
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "permission-test",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to "create_requirement",
                "arguments" to mapOf(
                    "title" to "Unauthorized Requirement",
                    "description" to "This should fail due to permissions"
                )
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", limitedApiKey)
            .header("Content-Type", "application/json")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)

        val errorBody = exception.response.getBody(JsonNode::class.java).get()
        assertEquals(-32603, errorBody.get("code").asInt()) // Internal error (forbidden)
        assertTrue(errorBody.get("message").asText().contains("permission", ignoreCase = true))
    }

    @Test
    fun `POST tools call returns 401 without API key`() {
        // Arrange
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "unauthorized-test",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to "get_requirements",
                "arguments" to mapOf("limit" to 5)
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("Content-Type", "application/json")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    fun `POST tools call validates JSON-RPC request structure`() {
        // Arrange - Missing required params.name field
        val apiKey = "test-api-key-12345"
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "invalid-structure-test",
            "method" to "tools/call",
            "params" to mapOf(
                "arguments" to mapOf("limit" to 5)
                // Missing "name" field
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)

        val errorBody = exception.response.getBody(JsonNode::class.java).get()
        assertEquals(-32602, errorBody.get("error").get("code").asInt()) // Invalid params
    }

    @Test
    fun `POST tools call handles tool execution errors gracefully`() {
        // Arrange - Tool that should cause internal error (e.g., database issue)
        val apiKey = "test-api-key-12345"
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "error-handling-test",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to "get_requirements",
                "arguments" to mapOf(
                    "query" to "trigger_internal_error", // Special query to simulate error
                    "limit" to 5
                )
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert - Should return successful HTTP response but with isError=true in content
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body()!!
        val result = body.get("result")

        assertTrue(result.has("isError"))
        assertTrue(result.get("isError").asBoolean())

        val content = result.get("content")
        val errorContent = content.get(0)
        assertEquals("text", errorContent.get("type").asText())
        assertTrue(errorContent.get("text").asText().contains("error", ignoreCase = true))
    }

    @Test
    fun `POST tools call measures execution time for audit logging`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "timing-test",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to "get_requirements",
                "arguments" to mapOf("limit" to 10)
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act
        val startTime = System.currentTimeMillis()
        val response = client.toBlocking().exchange(request, JsonNode::class.java)
        val endTime = System.currentTimeMillis()

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        // Execution time should be reasonable (less than 5 seconds for test)
        val executionTime = endTime - startTime
        assertTrue(executionTime < 5000, "Tool execution took too long: ${executionTime}ms")

        // Response should be successful
        val body = response.body()!!
        val result = body.get("result")
        assertFalse(result.get("isError").asBoolean(false))
    }
}