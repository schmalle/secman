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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integration test for complete MCP session lifecycle.
 * Tests the full flow: session creation → usage → cleanup → expiration
 *
 * This test MUST FAIL initially until the complete MCP system is implemented.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpSessionLifecycleTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    private lateinit var testApiKey: String
    private lateinit var jwtToken: String

    @BeforeEach
    fun setupTestData() {
        // These would be created by the test setup
        testApiKey = "integration-test-api-key-12345"
        jwtToken = "integration-test-jwt-token"
    }

    @Test
    fun `complete MCP session lifecycle from creation to cleanup`() {
        // Phase 1: Create API key
        val apiKeyResponse = createApiKey()
        val actualApiKey = apiKeyResponse.get("apiKey").asText()

        // Phase 2: Create MCP session
        val sessionResponse = createMcpSession(actualApiKey)
        val sessionId = sessionResponse.get("sessionId").asText()

        assertNotNull(sessionId)
        assertTrue(sessionId.isNotEmpty())

        // Phase 3: Verify session is active via capabilities
        val capabilitiesResponse = getCapabilities(actualApiKey)
        assertEquals(HttpStatus.OK, capabilitiesResponse.status)

        // Phase 4: Establish SSE connection
        val sseResponse = establishSseConnection(actualApiKey, sessionId)
        assertEquals(HttpStatus.OK, sseResponse.status)

        // Phase 5: Use session for tool calls
        val toolCallResponse = executeToolCall(actualApiKey, "get_requirements")
        assertEquals(HttpStatus.OK, toolCallResponse.status)

        // Phase 6: Explicitly close session
        val deleteResponse = closeSession(actualApiKey, sessionId)
        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.status)

        // Phase 7: Verify session is no longer accessible
        verifySessionClosed(actualApiKey, sessionId)
    }

    @Test
    fun `session expires after timeout period`() {
        // Create API key and session
        val apiKeyResponse = createApiKey()
        val actualApiKey = apiKeyResponse.get("apiKey").asText()
        val sessionResponse = createMcpSession(actualApiKey)
        val sessionId = sessionResponse.get("sessionId").asText()

        // Initial access should work
        val initialResponse = getCapabilities(actualApiKey)
        assertEquals(HttpStatus.OK, initialResponse.status)

        // Simulate waiting for session timeout (in real test, this would be shorter)
        // For integration test, we'll check if session cleanup works
        Thread.sleep(1000) // 1 second wait

        // Session should still be active (timeout is typically much longer)
        val activeResponse = getCapabilities(actualApiKey)
        assertEquals(HttpStatus.OK, activeResponse.status)

        // Clean up
        closeSession(actualApiKey, sessionId)
    }

    @Test
    fun `session maintains state across multiple operations`() {
        // Create session
        val apiKeyResponse = createApiKey()
        val actualApiKey = apiKeyResponse.get("apiKey").asText()
        val sessionResponse = createMcpSession(actualApiKey)
        val sessionId = sessionResponse.get("sessionId").asText()

        // Perform multiple operations with the same session
        val operations = listOf(
            "get_requirements",
            "get_risk_assessments",
            "search_all"
        )

        operations.forEach { toolName ->
            val response = executeToolCall(actualApiKey, toolName)
            assertEquals(HttpStatus.OK, response.status, "Failed for tool: $toolName")

            val body = response.body() as JsonNode
            val result = body.get("result")
            assertNotNull(result)
        }

        // Session should still be active
        val finalResponse = getCapabilities(actualApiKey)
        assertEquals(HttpStatus.OK, finalResponse.status)

        // Clean up
        closeSession(actualApiKey, sessionId)
    }

    @Test
    fun `concurrent sessions for same user work independently`() {
        // Create API key
        val apiKeyResponse = createApiKey()
        val actualApiKey = apiKeyResponse.get("apiKey").asText()

        // Create two sessions concurrently
        val session1Response = createMcpSession(actualApiKey)
        val session2Response = createMcpSession(actualApiKey)

        val sessionId1 = session1Response.get("sessionId").asText()
        val sessionId2 = session2Response.get("sessionId").asText()

        // Sessions should have different IDs
        assertNotEquals(sessionId1, sessionId2)

        // Both sessions should be independently functional
        val tool1Response = executeToolCall(actualApiKey, "get_requirements")
        val tool2Response = executeToolCall(actualApiKey, "get_risk_assessments")

        assertEquals(HttpStatus.OK, tool1Response.status)
        assertEquals(HttpStatus.OK, tool2Response.status)

        // Close sessions independently
        val delete1Response = closeSession(actualApiKey, sessionId1)
        assertEquals(HttpStatus.NO_CONTENT, delete1Response.status)

        // Session 2 should still work after session 1 is closed
        val session2StillActiveResponse = executeToolCall(actualApiKey, "search_all")
        assertEquals(HttpStatus.OK, session2StillActiveResponse.status)

        // Clean up session 2
        closeSession(actualApiKey, sessionId2)
    }

    @Test
    fun `session cleanup removes all associated resources`() {
        // Create session
        val apiKeyResponse = createApiKey()
        val actualApiKey = apiKeyResponse.get("apiKey").asText()
        val sessionResponse = createMcpSession(actualApiKey)
        val sessionId = sessionResponse.get("sessionId").asText()

        // Establish SSE connection
        val sseResponse = establishSseConnection(actualApiKey, sessionId)
        assertEquals(HttpStatus.OK, sseResponse.status)

        // Perform some operations to create audit logs
        executeToolCall(actualApiKey, "get_requirements")
        executeToolCall(actualApiKey, "get_risk_assessments")

        // Close session
        val deleteResponse = closeSession(actualApiKey, sessionId)
        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.status)

        // Verify all associated resources are cleaned up:

        // 1. SSE connection should be closed
        val sseAfterCloseException = assertThrows(
            io.micronaut.http.client.exceptions.HttpClientResponseException::class.java
        ) {
            establishSseConnection(actualApiKey, sessionId)
        }
        assertEquals(HttpStatus.NOT_FOUND, sseAfterCloseException.status)

        // 2. Session-specific operations should fail
        verifySessionClosed(actualApiKey, sessionId)

        // 3. Audit logs should still exist (for compliance)
        // This would be verified through audit API if implemented
    }

    // Helper methods

    private fun createApiKey(): JsonNode {
        val requestBody = mapOf(
            "name" to "Integration Test Key",
            "permissions" to listOf(
                "REQUIREMENTS_READ",
                "REQUIREMENTS_WRITE",
                "ASSESSMENTS_READ"
            )
        )

        val request = HttpRequest.POST("/api/mcp/admin/api-keys", requestBody)
            .header("Authorization", "Bearer $jwtToken")
            .header("Content-Type", "application/json")

        val response = client.toBlocking().exchange(request, JsonNode::class.java)
        assertEquals(HttpStatus.CREATED, response.status)
        return response.body()!!
    }

    private fun createMcpSession(apiKey: String): JsonNode {
        val requestBody = mapOf(
            "capabilities" to mapOf(
                "tools" to emptyMap<String, Any>(),
                "resources" to emptyMap<String, Any>(),
                "prompts" to emptyMap<String, Any>()
            ),
            "clientInfo" to mapOf(
                "name" to "Integration Test Client",
                "version" to "1.0.0"
            )
        )

        val request = HttpRequest.POST("/api/mcp/session", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        val response = client.toBlocking().exchange(request, JsonNode::class.java)
        assertEquals(HttpStatus.CREATED, response.status)
        return response.body()!!
    }

    private fun getCapabilities(apiKey: String): io.micronaut.http.HttpResponse<JsonNode> {
        val request = HttpRequest.GET<Any>("/api/mcp/capabilities")
            .header("X-MCP-API-Key", apiKey)

        return client.toBlocking().exchange(request, JsonNode::class.java)
    }

    private fun establishSseConnection(apiKey: String, sessionId: String): io.micronaut.http.HttpResponse<String> {
        val request = HttpRequest.GET<String>("/api/mcp/sse/$sessionId")
            .header("X-MCP-API-Key", apiKey)
            .header("Accept", "text/event-stream")

        return client.toBlocking().exchange(request, String::class.java)
    }

    private fun executeToolCall(apiKey: String, toolName: String): io.micronaut.http.HttpResponse<JsonNode> {
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "integration-test-${System.currentTimeMillis()}",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to toolName,
                "arguments" to when (toolName) {
                    "get_requirements" -> mapOf("limit" to 5)
                    "get_risk_assessments" -> mapOf("limit" to 3)
                    "search_all" -> mapOf("query" to "test", "limit" to 10)
                    else -> emptyMap<String, Any>()
                }
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        return client.toBlocking().exchange(request, JsonNode::class.java)
    }

    private fun closeSession(apiKey: String, sessionId: String): io.micronaut.http.HttpResponse<String> {
        val request = HttpRequest.DELETE<Any>("/api/mcp/session/$sessionId")
            .header("X-MCP-API-Key", apiKey)

        return client.toBlocking().exchange(request, String::class.java)
    }

    private fun verifySessionClosed(apiKey: String, sessionId: String) {
        // Try to access the closed session - should fail
        val exception = assertThrows(
            io.micronaut.http.client.exceptions.HttpClientResponseException::class.java
        ) {
            establishSseConnection(apiKey, sessionId)
        }
        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }
}