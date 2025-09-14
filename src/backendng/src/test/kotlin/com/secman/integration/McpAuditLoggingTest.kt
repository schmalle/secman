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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Integration test for MCP audit logging functionality.
 * Tests comprehensive audit trail for all MCP operations and security events.
 *
 * This test MUST FAIL initially until the complete MCP audit logging system is implemented.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpAuditLoggingTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    private lateinit var jwtToken: String
    private lateinit var adminJwtToken: String
    private lateinit var testApiKey: String
    private lateinit var adminApiKey: String
    private lateinit var sessionId: String

    @BeforeEach
    fun setupTestData() {
        jwtToken = "audit-test-jwt-token"
        adminJwtToken = "admin-audit-test-jwt-token"

        // Create regular user API key
        val apiKeyResponse = createApiKey("Audit Test Key", listOf(
            "REQUIREMENTS_READ", "REQUIREMENTS_WRITE", "ASSESSMENTS_READ"
        ))
        testApiKey = apiKeyResponse.get("apiKey").asText()

        // Create admin API key with audit permissions
        val adminApiKeyResponse = createAdminApiKey("Admin Audit Key", listOf(
            "REQUIREMENTS_READ", "AUDIT_READ"
        ))
        adminApiKey = adminApiKeyResponse.get("apiKey").asText()

        // Create session for testing
        val sessionResponse = createMcpSession(testApiKey)
        sessionId = sessionResponse.get("sessionId").asText()
    }

    @Test
    fun `authentication events are logged correctly`() {
        val testStartTime = LocalDateTime.now().minusSeconds(5)

        // Perform authentication operations that should be logged
        performAuthenticationOperations()

        // Wait a moment for async logging
        Thread.sleep(500)

        // Retrieve audit logs
        val auditLogs = getAuditLogs(testStartTime, null)

        // Verify authentication success events
        val authSuccessEvents = auditLogs.filter {
            it.get("eventType").asText() == "AUTH_SUCCESS"
        }
        assertTrue(authSuccessEvents.isNotEmpty(), "Should have AUTH_SUCCESS events")

        authSuccessEvents.forEach { event ->
            assertTrue(event.has("timestamp"))
            assertTrue(event.has("clientIp"))
            assertTrue(event.has("userAgent"))
            assertTrue(event.has("executionTimeMs"))
            assertTrue(event.get("executionTimeMs").asLong() >= 0)
        }

        // Test authentication failure logging
        val failureStartTime = LocalDateTime.now()

        // Attempt with invalid API key (should log AUTH_FAILURE)
        try {
            getCapabilities("invalid-api-key")
        } catch (e: Exception) {
            // Expected to fail
        }

        Thread.sleep(500)

        val failureLogs = getAuditLogs(failureStartTime, null)
        val authFailureEvents = failureLogs.filter {
            it.get("eventType").asText() == "AUTH_FAILURE"
        }
        assertTrue(authFailureEvents.isNotEmpty(), "Should have AUTH_FAILURE events")
    }

    @Test
    fun `tool call events are logged with complete details`() {
        val testStartTime = LocalDateTime.now().minusSeconds(5)

        // Execute various tool calls
        executeToolCall("get_requirements", mapOf("limit" to 5))
        executeToolCall("search_all", mapOf("query" to "test", "limit" to 10))

        // Execute a tool call that should fail (for error logging)
        try {
            executeToolCall("get_requirements", mapOf("limit" to -1)) // Invalid limit
        } catch (e: Exception) {
            // Expected to fail
        }

        Thread.sleep(1000) // Wait for logging

        val auditLogs = getAuditLogs(testStartTime, null)

        // Verify tool call events
        val toolCallEvents = auditLogs.filter {
            it.get("eventType").asText() == "TOOL_CALL"
        }
        assertTrue(toolCallEvents.size >= 2, "Should have at least 2 TOOL_CALL events")

        toolCallEvents.forEach { event ->
            // Validate required fields
            assertTrue(event.has("toolName"))
            assertTrue(event.has("method"))
            assertTrue(event.has("requestParams"))
            assertTrue(event.has("responseStatus"))
            assertTrue(event.has("executionTimeMs"))
            assertTrue(event.has("timestamp"))

            // Validate tool name is one of the called tools
            val toolName = event.get("toolName").asText()
            assertTrue(setOf("get_requirements", "search_all").contains(toolName))

            // Validate method
            assertEquals("tools/call", event.get("method").asText())

            // Validate execution time is reasonable
            val execTime = event.get("executionTimeMs").asLong()
            assertTrue(execTime >= 0 && execTime < 10000, "Execution time unreasonable: $execTime")

            // Check request params are sanitized (no sensitive data)
            val requestParams = event.get("requestParams").asText()
            assertNotNull(requestParams)
            assertFalse(requestParams.contains("password", ignoreCase = true))
            assertFalse(requestParams.contains("secret", ignoreCase = true))
        }
    }

    @Test
    fun `session lifecycle events are tracked`() {
        val testStartTime = LocalDateTime.now().minusSeconds(5)

        // Create and manage session lifecycle
        val newSessionResponse = createMcpSession(testApiKey)
        val newSessionId = newSessionResponse.get("sessionId").asText()

        // Use the session
        establishSseConnection(testApiKey, newSessionId)
        executeToolCall("get_requirements", mapOf("limit" to 3))

        // Close the session
        closeSession(testApiKey, newSessionId)

        Thread.sleep(1000) // Wait for logging

        val auditLogs = getAuditLogs(testStartTime, null)

        // Verify session events
        val sessionEvents = auditLogs.filter {
            val eventType = it.get("eventType").asText()
            setOf("SESSION_CREATE", "SESSION_CLOSE", "SSE_CONNECT").contains(eventType)
        }

        assertTrue(sessionEvents.isNotEmpty(), "Should have session lifecycle events")

        // Verify SESSION_CREATE event
        val createEvents = sessionEvents.filter {
            it.get("eventType").asText() == "SESSION_CREATE"
        }
        assertTrue(createEvents.isNotEmpty(), "Should have SESSION_CREATE event")

        createEvents.forEach { event ->
            assertTrue(event.has("sessionId"))
            assertTrue(event.has("clientInfo") || event.has("resourcePath"))
        }

        // Verify SESSION_CLOSE event
        val closeEvents = sessionEvents.filter {
            it.get("eventType").asText() == "SESSION_CLOSE"
        }
        assertTrue(closeEvents.isNotEmpty(), "Should have SESSION_CLOSE event")
    }

    @Test
    fun `permission denied events are logged with security context`() {
        val testStartTime = LocalDateTime.now().minusSeconds(5)

        // Create API key with limited permissions
        val limitedKeyResponse = createApiKey("Limited Permissions Key", listOf("REQUIREMENTS_READ"))
        val limitedApiKey = limitedKeyResponse.get("apiKey").asText()

        // Try to perform operation without sufficient permissions
        try {
            executeToolCallWithKey(limitedApiKey, "create_requirement", mapOf(
                "title" to "Unauthorized Requirement",
                "description" to "This should be denied"
            ))
        } catch (e: Exception) {
            // Expected to fail
        }

        Thread.sleep(500)

        val auditLogs = getAuditLogs(testStartTime, null)

        // Verify PERMISSION_DENIED events
        val permissionEvents = auditLogs.filter {
            it.get("eventType").asText() == "PERMISSION_DENIED"
        }
        assertTrue(permissionEvents.isNotEmpty(), "Should have PERMISSION_DENIED events")

        permissionEvents.forEach { event ->
            assertTrue(event.has("toolName"))
            assertTrue(event.has("clientIp"))
            assertTrue(event.has("errorMessage"))

            assertEquals("create_requirement", event.get("toolName").asText())
            val errorMsg = event.get("errorMessage").asText()
            assertTrue(errorMsg.contains("permission", ignoreCase = true) ||
                      errorMsg.contains("unauthorized", ignoreCase = true))
        }
    }

    @Test
    fun `audit log query and filtering works correctly`() {
        val testStartTime = LocalDateTime.now().minusSeconds(5)

        // Generate various types of events
        getCapabilities(testApiKey) // AUTH_SUCCESS
        executeToolCall("get_requirements", mapOf("limit" to 5)) // TOOL_CALL
        executeToolCall("search_all", mapOf("query" to "audit", "limit" to 3)) // TOOL_CALL

        Thread.sleep(1000)

        val testEndTime = LocalDateTime.now()

        // Test date range filtering
        val filteredLogs = getAuditLogs(testStartTime, testEndTime)
        assertTrue(filteredLogs.isNotEmpty(), "Should have logs in date range")

        // Test event type filtering
        val toolCallLogs = getAuditLogsByEventType("TOOL_CALL", testStartTime, testEndTime)
        assertTrue(toolCallLogs.isNotEmpty(), "Should have TOOL_CALL logs")

        toolCallLogs.forEach { log ->
            assertEquals("TOOL_CALL", log.get("eventType").asText())
        }

        // Test pagination
        val pagedLogs = getAuditLogsWithPagination(testStartTime, testEndTime, 2, 0)
        assertTrue(pagedLogs.size <= 2, "Should respect limit parameter")

        // Verify log ordering (should be newest first)
        if (pagedLogs.size > 1) {
            val first = LocalDateTime.parse(pagedLogs[0].get("timestamp").asText(),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val second = LocalDateTime.parse(pagedLogs[1].get("timestamp").asText(),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            assertTrue(first.isAfter(second) || first.isEqual(second),
                "Logs should be ordered newest first")
        }
    }

    @Test
    fun `sensitive data is properly sanitized in logs`() {
        val testStartTime = LocalDateTime.now().minusSeconds(5)

        // Execute tool call with potentially sensitive data
        executeToolCall("create_requirement", mapOf(
            "title" to "Test Requirement with Sensitive Data",
            "description" to "Contains password: secret123 and token: abc-xyz-789",
            "category" to "Security"
        ))

        Thread.sleep(500)

        val auditLogs = getAuditLogs(testStartTime, null)
        val toolCallEvents = auditLogs.filter {
            it.get("eventType").asText() == "TOOL_CALL" &&
            it.get("toolName").asText() == "create_requirement"
        }

        assertTrue(toolCallEvents.isNotEmpty(), "Should have create_requirement events")

        toolCallEvents.forEach { event ->
            val requestParams = event.get("requestParams").asText()

            // Sensitive data should be sanitized or redacted
            assertFalse(requestParams.contains("secret123"), "Passwords should be sanitized")
            assertFalse(requestParams.contains("abc-xyz-789"), "Tokens should be sanitized")

            // Non-sensitive data should still be present
            assertTrue(requestParams.contains("Test Requirement"), "Non-sensitive data should remain")
        }
    }

    @Test
    fun `audit logs support compliance retention requirements`() {
        val testStartTime = LocalDateTime.now().minusSeconds(5)

        // Generate security-relevant events
        getCapabilities(testApiKey)
        executeToolCall("get_requirements", mapOf("limit" to 1))

        // Try invalid operation (security event)
        try {
            getCapabilities("invalid-key")
        } catch (e: Exception) {
            // Expected
        }

        Thread.sleep(500)

        val auditLogs = getAuditLogs(testStartTime, null)

        // Verify logs contain compliance-required fields
        auditLogs.forEach { log ->
            // Required for compliance
            assertTrue(log.has("timestamp"), "Must have timestamp for compliance")
            assertTrue(log.has("eventType"), "Must have event type")
            assertTrue(log.has("clientIp"), "Must have client IP for security")

            // For tool calls
            if (log.get("eventType").asText() == "TOOL_CALL") {
                assertTrue(log.has("toolName"), "Tool calls must log tool name")
                assertTrue(log.has("responseStatus"), "Must log response status")
                assertTrue(log.has("executionTimeMs"), "Must log execution time")
            }

            // For authentication events
            if (log.get("eventType").asText() == "AUTH_FAILURE") {
                assertTrue(log.has("errorMessage"), "Auth failures must log error")
                assertTrue(log.has("userAgent"), "Must log user agent for forensics")
            }

            // Validate timestamp format
            val timestamp = log.get("timestamp").asText()
            assertDoesNotThrow {
                LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            }
        }
    }

    @Test
    fun `audit logging performance is acceptable`() {
        val operationCount = 20
        val startTime = System.currentTimeMillis()

        // Perform operations that will be logged
        repeat(operationCount) { i ->
            executeToolCall("get_requirements", mapOf(
                "limit" to 2,
                "offset" to i * 2
            ))
        }

        val operationEndTime = System.currentTimeMillis()
        val operationDuration = operationEndTime - startTime

        // Operations should complete quickly (logging shouldn't slow things down significantly)
        assertTrue(operationDuration < 10000, // 10 seconds
            "Operations took too long with audit logging: ${operationDuration}ms")

        // Average time per operation should be reasonable
        val avgTimePerOp = operationDuration / operationCount
        assertTrue(avgTimePerOp < 500, // 500ms per operation
            "Average operation time too high: ${avgTimePerOp}ms")

        // Wait for all logs to be written
        Thread.sleep(2000)

        // Verify all operations were logged
        val testEndTime = LocalDateTime.now()
        val operationStartTime = LocalDateTime.now().minusMinutes(1)

        val auditLogs = getAuditLogs(operationStartTime, testEndTime)
        val toolCallLogs = auditLogs.filter {
            it.get("eventType").asText() == "TOOL_CALL" &&
            it.get("toolName").asText() == "get_requirements"
        }

        assertTrue(toolCallLogs.size >= operationCount * 0.9,
            "Most operations should be logged: ${toolCallLogs.size}/$operationCount")
    }

    // Helper methods

    private fun createApiKey(name: String, permissions: List<String>): JsonNode {
        return createApiKeyForUser(jwtToken, name, permissions)
    }

    private fun createAdminApiKey(name: String, permissions: List<String>): JsonNode {
        return createApiKeyForUser(adminJwtToken, name, permissions)
    }

    private fun createApiKeyForUser(token: String, name: String, permissions: List<String>): JsonNode {
        val requestBody = mapOf(
            "name" to name,
            "permissions" to permissions
        )

        val request = HttpRequest.POST("/api/mcp/admin/api-keys", requestBody)
            .header("Authorization", "Bearer $token")
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
                "name" to "Audit Test Client",
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

    private fun getCapabilities(apiKey: String): JsonNode {
        val request = HttpRequest.GET<Any>("/api/mcp/capabilities")
            .header("X-MCP-API-Key", apiKey)

        val response = client.toBlocking().exchange(request, JsonNode::class.java)
        return response.body()!!
    }

    private fun executeToolCall(toolName: String, arguments: Map<String, Any>): JsonNode {
        return executeToolCallWithKey(testApiKey, toolName, arguments)
    }

    private fun executeToolCallWithKey(apiKey: String, toolName: String, arguments: Map<String, Any>): JsonNode {
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "audit-test-${System.currentTimeMillis()}",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to toolName,
                "arguments" to arguments
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        val response = client.toBlocking().exchange(request, JsonNode::class.java)
        return response.body()!!
    }

    private fun establishSseConnection(apiKey: String, sessionId: String): String {
        val request = HttpRequest.GET<String>("/api/mcp/sse/$sessionId")
            .header("X-MCP-API-Key", apiKey)
            .header("Accept", "text/event-stream")

        val response = client.toBlocking().exchange(request, String::class.java)
        return response.body()!!
    }

    private fun closeSession(apiKey: String, sessionId: String) {
        val request = HttpRequest.DELETE<Any>("/api/mcp/session/$sessionId")
            .header("X-MCP-API-Key", apiKey)

        client.toBlocking().exchange(request, String::class.java)
    }

    private fun performAuthenticationOperations() {
        // Multiple operations that should generate AUTH_SUCCESS events
        getCapabilities(testApiKey)
        createMcpSession(testApiKey)
        getCapabilities(adminApiKey)
    }

    private fun getAuditLogs(startTime: LocalDateTime?, endTime: LocalDateTime?): List<JsonNode> {
        return getAuditLogsWithPagination(startTime, endTime, 100, 0)
    }

    private fun getAuditLogsByEventType(eventType: String, startTime: LocalDateTime?, endTime: LocalDateTime?): List<JsonNode> {
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "audit-query-${System.currentTimeMillis()}",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to "get_audit_log",
                "arguments" to mapOf(
                    "eventType" to eventType,
                    "startDate" to startTime?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    "endDate" to endTime?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    "limit" to 100
                ).filterValues { it != null }
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", adminApiKey)
            .header("Content-Type", "application/json")

        val response = client.toBlocking().exchange(request, JsonNode::class.java)
        val result = response.body()!!.get("result")
        val contentText = result.get("content").get(0).get("text").asText()
        val auditData = com.fasterxml.jackson.databind.ObjectMapper().readTree(contentText)

        return auditData.get("events").map { it }.toList()
    }

    private fun getAuditLogsWithPagination(startTime: LocalDateTime?, endTime: LocalDateTime?,
                                         limit: Int, offset: Int): List<JsonNode> {
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "audit-query-${System.currentTimeMillis()}",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to "get_audit_log",
                "arguments" to mapOf(
                    "startDate" to startTime?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    "endDate" to endTime?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    "limit" to limit,
                    "offset" to offset
                ).filterValues { it != null }
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", adminApiKey)
            .header("Content-Type", "application/json")

        val response = client.toBlocking().exchange(request, JsonNode::class.java)
        val result = response.body()!!.get("result")
        val contentText = result.get("content").get(0).get("text").asText()
        val auditData = com.fasterxml.jackson.databind.ObjectMapper().readTree(contentText)

        return auditData.get("events").map { it }.toList()
    }
}