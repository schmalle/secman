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
 * Contract test for MCP tools list endpoint.
 * Tests the POST /api/mcp/tools/list endpoint according to OpenAPI specification.
 *
 * This test MUST FAIL initially until the endpoint is implemented.
 */
@MicronautTest
class McpToolsListTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `POST tools list returns available MCP tools with valid API key`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "1",
            "method" to "tools/list",
            "params" to emptyMap<String, Any>()
        )

        val request = HttpRequest.POST("/api/mcp/tools/list", requestBody)
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
        assertEquals("1", body.get("id").asText())

        // Validate tools list result
        val result = body.get("result")
        assertTrue(result.has("tools"))

        val tools = result.get("tools")
        assertTrue(tools.isArray)
        assertTrue(tools.size() > 0) // Should have at least some tools

        // Validate first tool structure (should match McpTool schema)
        val firstTool = tools.get(0)
        assertTrue(firstTool.has("name"))
        assertTrue(firstTool.has("description"))
        assertTrue(firstTool.has("inputSchema"))

        // Expected tool names from design documents
        val toolNames = tools.map { it.get("name").asText() }.toSet()
        assertTrue(toolNames.contains("get_requirements"))
        assertTrue(toolNames.contains("create_requirement"))
        assertTrue(toolNames.contains("get_risk_assessments"))
    }

    @Test
    fun `POST tools list includes all expected tools from specification`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "tools-list-test",
            "method" to "tools/list",
            "params" to emptyMap<String, Any>()
        )

        val request = HttpRequest.POST("/api/mcp/tools/list", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val result = response.body()!!.get("result")
        val tools = result.get("tools")
        val toolNames = tools.map { it.get("name").asText() }.toSet()

        // Validate all expected tools are present based on mcp-tools.yaml
        val expectedTools = setOf(
            "get_requirements",
            "create_requirement",
            "update_requirement",
            "get_risk_assessments",
            "execute_risk_assessment",
            "get_requirement_files",
            "download_file",
            "translate_requirement",
            "get_audit_log",
            "search_all"
        )

        expectedTools.forEach { expectedTool ->
            assertTrue(toolNames.contains(expectedTool), "Missing tool: $expectedTool")
        }
    }

    @Test
    fun `POST tools list filters tools based on user permissions`() {
        // Arrange - API key with limited permissions
        val limitedApiKey = "limited-api-key-12345"
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "permissions-test",
            "method" to "tools/list",
            "params" to emptyMap<String, Any>()
        )

        val request = HttpRequest.POST("/api/mcp/tools/list", requestBody)
            .header("X-MCP-API-Key", limitedApiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val result = response.body()!!.get("result")
        val tools = result.get("tools")
        val toolNames = tools.map { it.get("name").asText() }.toSet()

        // Admin-only tools should not be included for limited permissions
        assertFalse(toolNames.contains("get_audit_log"))

        // Basic tools should still be available
        assertTrue(toolNames.contains("get_requirements"))
    }

    @Test
    fun `POST tools list validates JSON-RPC request format`() {
        // Arrange - Invalid JSON-RPC format
        val apiKey = "test-api-key-12345"
        val invalidRequestBody = mapOf(
            "method" to "tools/list",
            // Missing required jsonrpc and id fields
            "params" to emptyMap<String, Any>()
        )

        val request = HttpRequest.POST("/api/mcp/tools/list", invalidRequestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)

        val errorBody = exception.response.getBody(JsonNode::class.java).get()
        assertTrue(errorBody.has("jsonrpc"))
        assertTrue(errorBody.has("error"))

        val error = errorBody.get("error")
        assertEquals(-32600, error.get("code").asInt()) // Invalid Request
    }

    @Test
    fun `POST tools list returns 401 without API key`() {
        // Arrange
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "unauthorized-test",
            "method" to "tools/list",
            "params" to emptyMap<String, Any>()
        )

        val request = HttpRequest.POST("/api/mcp/tools/list", requestBody)
            .header("Content-Type", "application/json")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    fun `POST tools list includes proper tool schemas`() {
        // Arrange
        val apiKey = "test-api-key-12345"
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "schema-test",
            "method" to "tools/list",
            "params" to emptyMap<String, Any>()
        )

        val request = HttpRequest.POST("/api/mcp/tools/list", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val tools = response.body()!!.get("result").get("tools")

        // Find get_requirements tool and validate its schema
        val getRequirementsTool = tools.find { it.get("name").asText() == "get_requirements" }
        assertNotNull(getRequirementsTool)

        assertTrue(getRequirementsTool!!.has("inputSchema"))
        assertTrue(getRequirementsTool.has("description"))

        val inputSchema = getRequirementsTool.get("inputSchema")
        assertTrue(inputSchema.has("type"))
        assertEquals("object", inputSchema.get("type").asText())

        // Should have properties defined in GetRequirementsInput
        if (inputSchema.has("properties")) {
            val properties = inputSchema.get("properties")
            // These properties should exist based on the tool contract
            assertTrue(properties.has("query") || properties.has("limit") ||
                      properties.has("category") || properties.has("status"))
        }
    }

    @Test
    fun `POST tools list handles method parameter correctly`() {
        // Arrange - Wrong method name should return method not found
        val apiKey = "test-api-key-12345"
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "method-test",
            "method" to "tools/invalid",
            "params" to emptyMap<String, Any>()
        )

        val request = HttpRequest.POST("/api/mcp/tools/list", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)

        val errorBody = exception.response.getBody(JsonNode::class.java).get()
        val error = errorBody.get("error")
        assertEquals(-32601, error.get("code").asInt()) // Method not found
    }
}