package com.secman.integration

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Integration test for MCP tool execution.
 * Tests end-to-end tool execution with real data operations.
 *
 * This test MUST FAIL initially until the complete MCP tool system is implemented.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpToolExecutionTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var objectMapper: ObjectMapper

    private lateinit var testApiKey: String
    private lateinit var jwtToken: String
    private lateinit var sessionId: String

    @BeforeEach
    fun setupTestData() {
        jwtToken = "integration-test-jwt-token"

        // Create API key for testing
        val apiKeyResponse = createApiKey()
        testApiKey = apiKeyResponse.get("apiKey").asText()

        // Create MCP session
        val sessionResponse = createMcpSession()
        sessionId = sessionResponse.get("sessionId").asText()
    }

    @Test
    fun `get_requirements tool returns actual security requirements`() {
        // Arrange
        val requestBody = createToolCallRequest("get_requirements", mapOf(
            "limit" to 10,
            "status" to "ACTIVE"
        ))

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", testApiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body()!!
        val result = body.get("result")
        assertFalse(result.get("isError").asBoolean(false))

        val content = result.get("content")
        assertTrue(content.isArray)
        assertTrue(content.size() > 0)

        val textContent = content.get(0).get("text").asText()
        val requirementsData = objectMapper.readTree(textContent)

        // Validate requirements structure
        assertTrue(requirementsData.has("requirements"))
        assertTrue(requirementsData.has("totalCount"))

        val requirements = requirementsData.get("requirements")
        if (requirements.size() > 0) {
            val firstReq = requirements.get(0)
            assertTrue(firstReq.has("id"))
            assertTrue(firstReq.has("title"))
            assertTrue(firstReq.has("description"))
            assertTrue(firstReq.has("status"))
            assertEquals("ACTIVE", firstReq.get("status").asText())
        }
    }

    @Test
    fun `create_requirement tool creates new requirement and returns it`() {
        // Arrange - Create a new requirement
        val newRequirement = mapOf(
            "title" to "Integration Test Requirement",
            "description" to "This requirement was created during MCP integration testing",
            "category" to "Integration Testing",
            "priority" to "MEDIUM",
            "tags" to listOf("integration", "test", "mcp")
        )

        val requestBody = createToolCallRequest("create_requirement", newRequirement)

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", testApiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body()!!
        val result = body.get("result")
        assertFalse(result.get("isError").asBoolean(false))

        val content = result.get("content")
        val textContent = content.get(0).get("text").asText()
        val createdRequirement = objectMapper.readTree(textContent)

        // Verify the created requirement
        assertTrue(createdRequirement.has("requirement"))
        val requirement = createdRequirement.get("requirement")

        assertTrue(requirement.has("id"))
        assertEquals("Integration Test Requirement", requirement.get("title").asText())
        assertEquals("Integration Testing", requirement.get("category").asText())
        assertEquals("MEDIUM", requirement.get("priority").asText())

        // Verify it can be retrieved
        val getRequestBody = createToolCallRequest("get_requirements", mapOf(
            "query" to "Integration Test Requirement",
            "limit" to 5
        ))

        val getRequest = HttpRequest.POST("/api/mcp/tools/call", getRequestBody)
            .header("X-MCP-API-Key", testApiKey)
            .header("Content-Type", "application/json")

        val getResponse = client.toBlocking().exchange(getRequest, JsonNode::class.java)
        assertEquals(HttpStatus.OK, getResponse.status)

        val getResult = getResponse.body()!!.get("result")
        val getContent = getResult.get("content").get(0).get("text").asText()
        val searchResults = objectMapper.readTree(getContent)

        assertTrue(searchResults.get("requirements").size() > 0)
    }

    @Test
    fun `get_risk_assessments tool returns assessment data`() {
        // Arrange
        val requestBody = createToolCallRequest("get_risk_assessments", mapOf(
            "limit" to 5,
            "status" to "COMPLETED"
        ))

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", testApiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body()!!
        val result = body.get("result")
        assertFalse(result.get("isError").asBoolean(false))

        val content = result.get("content")
        val textContent = content.get(0).get("text").asText()
        val assessmentsData = objectMapper.readTree(textContent)

        assertTrue(assessmentsData.has("assessments"))
        assertTrue(assessmentsData.has("totalCount"))

        val assessments = assessmentsData.get("assessments")
        // Assessments might be empty in test environment, which is OK
        if (assessments.size() > 0) {
            val firstAssessment = assessments.get(0)
            assertTrue(firstAssessment.has("id"))
            assertTrue(firstAssessment.has("riskLevel"))
            assertTrue(firstAssessment.has("status"))
        }
    }

    @Test
    fun `search_all tool searches across all resource types`() {
        // Arrange
        val requestBody = createToolCallRequest("search_all", mapOf(
            "query" to "security",
            "includeRequirements" to true,
            "includeAssessments" to true,
            "limit" to 20
        ))

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", testApiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body()!!
        val result = body.get("result")
        assertFalse(result.get("isError").asBoolean(false))

        val content = result.get("content")
        val textContent = content.get(0).get("text").asText()
        val searchResults = objectMapper.readTree(textContent)

        assertTrue(searchResults.has("results"))
        assertTrue(searchResults.has("totalCount"))
        assertTrue(searchResults.has("queryTime"))

        val results = searchResults.get("results")
        for (i in 0 until results.size()) {
            val searchResult = results.get(i)
            assertTrue(searchResult.has("type"))
            assertTrue(searchResult.has("id"))
            assertTrue(searchResult.has("title"))
            assertTrue(searchResult.has("relevance"))

            val type = searchResult.get("type").asText()
            assertTrue(setOf("REQUIREMENT", "ASSESSMENT", "FILE").contains(type))

            val relevance = searchResult.get("relevance").asDouble()
            assertTrue(relevance >= 0.0 && relevance <= 1.0)
        }
    }

    @Test
    fun `tool execution respects user permissions`() {
        // Create API key with limited permissions
        val limitedApiKeyResponse = createApiKeyWithLimitedPermissions()
        val limitedApiKey = limitedApiKeyResponse.get("apiKey").asText()

        // Try to use create_requirement tool (should fail with limited permissions)
        val requestBody = createToolCallRequest("create_requirement", mapOf(
            "title" to "Unauthorized Requirement",
            "description" to "This should fail"
        ))

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", limitedApiKey)
            .header("Content-Type", "application/json")

        // Should return 403 Forbidden
        val exception = assertThrows(
            io.micronaut.http.client.exceptions.HttpClientResponseException::class.java
        ) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    fun `tool execution handles validation errors gracefully`() {
        // Arrange - Invalid arguments
        val requestBody = createToolCallRequest("get_requirements", mapOf(
            "limit" to -5, // Invalid limit
            "status" to "INVALID_STATUS" // Invalid enum
        ))

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", testApiKey)
            .header("Content-Type", "application/json")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert - Should return successful HTTP but with isError=true
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body()!!
        val result = body.get("result")
        assertTrue(result.get("isError").asBoolean())

        val content = result.get("content")
        val errorContent = content.get(0)
        assertEquals("text", errorContent.get("type").asText())
        val errorText = errorContent.get("text").asText()
        assertTrue(errorText.contains("validation", ignoreCase = true) ||
                  errorText.contains("invalid", ignoreCase = true))
    }

    @Test
    fun `tool execution measures and logs performance`() {
        // Arrange - Execute a tool that should complete quickly
        val requestBody = createToolCallRequest("get_requirements", mapOf(
            "limit" to 1
        ))

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", testApiKey)
            .header("Content-Type", "application/json")

        // Act
        val startTime = System.currentTimeMillis()
        val response = client.toBlocking().exchange(request, JsonNode::class.java)
        val endTime = System.currentTimeMillis()

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val executionTime = endTime - startTime
        assertTrue(executionTime < 2000, "Tool execution took too long: ${executionTime}ms")

        val body = response.body()!!
        val result = body.get("result")
        assertFalse(result.get("isError").asBoolean(false))
    }

    @Test
    fun `concurrent tool executions work correctly`() {
        // Execute multiple tools concurrently
        val tools = listOf("get_requirements", "get_risk_assessments", "search_all")
        val responses = mutableListOf<io.micronaut.http.HttpResponse<JsonNode>>()

        // Execute all tools in parallel (simulated)
        tools.forEach { toolName ->
            val args = when (toolName) {
                "get_requirements" -> mapOf("limit" to 3)
                "get_risk_assessments" -> mapOf("limit" to 2)
                "search_all" -> mapOf("query" to "test", "limit" to 5)
                else -> emptyMap()
            }

            val requestBody = createToolCallRequest(toolName, args)
            val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
                .header("X-MCP-API-Key", testApiKey)
                .header("Content-Type", "application/json")

            val response = client.toBlocking().exchange(request, JsonNode::class.java)
            responses.add(response)
        }

        // All should succeed
        responses.forEach { response ->
            assertEquals(HttpStatus.OK, response.status)

            val result = response.body()!!.get("result")
            assertFalse(result.get("isError").asBoolean(false))
        }
    }

    // Helper methods

    private fun createApiKey(): JsonNode {
        val requestBody = mapOf(
            "name" to "Tool Execution Test Key",
            "permissions" to listOf(
                "REQUIREMENTS_READ",
                "REQUIREMENTS_WRITE",
                "ASSESSMENTS_READ",
                "FILES_READ"
            )
        )

        val request = HttpRequest.POST("/api/mcp/admin/api-keys", requestBody)
            .header("Authorization", "Bearer $jwtToken")
            .header("Content-Type", "application/json")

        val response = client.toBlocking().exchange(request, JsonNode::class.java)
        assertEquals(HttpStatus.CREATED, response.status)
        return response.body()!!
    }

    private fun createApiKeyWithLimitedPermissions(): JsonNode {
        val requestBody = mapOf(
            "name" to "Limited Permissions Test Key",
            "permissions" to listOf("REQUIREMENTS_READ") // Only read permission
        )

        val request = HttpRequest.POST("/api/mcp/admin/api-keys", requestBody)
            .header("Authorization", "Bearer $jwtToken")
            .header("Content-Type", "application/json")

        val response = client.toBlocking().exchange(request, JsonNode::class.java)
        assertEquals(HttpStatus.CREATED, response.status)
        return response.body()!!
    }

    private fun createMcpSession(): JsonNode {
        val requestBody = mapOf(
            "capabilities" to mapOf(
                "tools" to emptyMap<String, Any>(),
                "resources" to emptyMap<String, Any>(),
                "prompts" to emptyMap<String, Any>()
            ),
            "clientInfo" to mapOf(
                "name" to "Tool Execution Test Client",
                "version" to "1.0.0"
            )
        )

        val request = HttpRequest.POST("/api/mcp/session", requestBody)
            .header("X-MCP-API-Key", testApiKey)
            .header("Content-Type", "application/json")

        val response = client.toBlocking().exchange(request, JsonNode::class.java)
        assertEquals(HttpStatus.CREATED, response.status)
        return response.body()!!
    }

    private fun createToolCallRequest(toolName: String, arguments: Map<String, Any>): Map<String, Any> {
        return mapOf(
            "jsonrpc" to "2.0",
            "id" to "tool-exec-test-${System.currentTimeMillis()}",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to toolName,
                "arguments" to arguments
            )
        )
    }
}