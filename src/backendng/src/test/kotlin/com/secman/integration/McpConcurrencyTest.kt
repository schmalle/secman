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
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/**
 * Integration test for concurrent MCP client operations.
 * Tests the system's ability to handle multiple simultaneous MCP clients and operations.
 *
 * This test MUST FAIL initially until the complete MCP concurrency system is implemented.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpConcurrencyTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    private lateinit var jwtToken: String
    private val executor = Executors.newFixedThreadPool(20)

    @BeforeEach
    fun setupTestData() {
        jwtToken = "concurrency-test-jwt-token"
    }

    @Test
    fun `handles 10 concurrent MCP sessions successfully`() {
        val sessionCount = 10
        val completedSessions = AtomicInteger(0)
        val errors = mutableListOf<Exception>()
        val latch = CountDownLatch(sessionCount)

        // Create API keys for concurrent sessions
        val apiKeys = mutableListOf<String>()
        repeat(sessionCount) { i ->
            val keyResponse = createApiKey("Concurrent Session Key $i")
            apiKeys.add(keyResponse.get("apiKey").asText())
        }

        // Create sessions concurrently
        val sessionIds = mutableListOf<String>()
        repeat(sessionCount) { i ->
            executor.submit {
                try {
                    val sessionResponse = createMcpSession(apiKeys[i])
                    synchronized(sessionIds) {
                        sessionIds.add(sessionResponse.get("sessionId").asText())
                    }
                    completedSessions.incrementAndGet()
                } catch (e: Exception) {
                    synchronized(errors) {
                        errors.add(e)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Wait for all sessions to complete
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Sessions took too long to create")

        // Assert all sessions were created successfully
        assertEquals(sessionCount, completedSessions.get(),
            "Not all sessions were created. Errors: $errors")
        assertEquals(sessionCount, sessionIds.size)

        // All session IDs should be unique
        assertEquals(sessionCount, sessionIds.toSet().size, "Session IDs are not unique")

        // Cleanup sessions
        sessionIds.forEachIndexed { index, sessionId ->
            closeSession(apiKeys[index], sessionId)
        }
    }

    @Test
    fun `handles concurrent tool calls without data corruption`() {
        // Create single session for concurrent tool calls
        val apiKeyResponse = createApiKey("Concurrent Tools Key")
        val apiKey = apiKeyResponse.get("apiKey").asText()
        val sessionResponse = createMcpSession(apiKey)

        val concurrentCalls = 20
        val successCount = AtomicInteger(0)
        val errors = mutableListOf<Exception>()
        val latch = CountDownLatch(concurrentCalls)

        val results = mutableListOf<JsonNode>()

        // Execute tool calls concurrently
        repeat(concurrentCalls) { i ->
            executor.submit {
                try {
                    val toolResponse = callTool(apiKey, "get_requirements", mapOf(
                        "limit" to 5,
                        "offset" to i * 5 // Different offset for each call
                    ))

                    if (toolResponse.status == HttpStatus.OK) {
                        synchronized(results) {
                            results.add(toolResponse.body()!!)
                        }
                        successCount.incrementAndGet()
                    }
                } catch (e: Exception) {
                    synchronized(errors) {
                        errors.add(e)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Wait for all calls to complete
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Tool calls took too long")

        // Assert most calls succeeded (some might fail due to rate limiting)
        assertTrue(successCount.get() >= concurrentCalls * 0.8,
            "Too many tool calls failed. Success: ${successCount.get()}, Errors: ${errors.size}")

        // Check response integrity
        results.forEach { result ->
            assertTrue(result.has("jsonrpc"))
            assertTrue(result.has("result"))
            val toolResult = result.get("result")
            assertNotNull(toolResult)
        }
    }

    @Test
    fun `handles concurrent API key operations safely`() {
        val keyOperations = 15
        val createdKeys = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        val latch = CountDownLatch(keyOperations)

        // Create API keys concurrently
        repeat(keyOperations) { i ->
            executor.submit {
                try {
                    val keyResponse = createApiKey("Concurrent Key $i")
                    synchronized(createdKeys) {
                        createdKeys.add(keyResponse.get("keyId").asText())
                    }
                } catch (e: Exception) {
                    synchronized(errors) {
                        errors.add(e)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(20, TimeUnit.SECONDS), "Key creation took too long")

        // Most keys should be created successfully
        assertTrue(createdKeys.size >= keyOperations * 0.9,
            "Too many key creations failed. Created: ${createdKeys.size}, Errors: ${errors.size}")

        // All key IDs should be unique (no race conditions in ID generation)
        assertEquals(createdKeys.size, createdKeys.toSet().size, "Key IDs are not unique")

        // Verify keys appear in list
        val listResponse = listApiKeys()
        val listedKeyIds = listResponse.body()!!.get("apiKeys")
            .map { it.get("keyId").asText() }.toSet()

        createdKeys.forEach { keyId ->
            assertTrue(listedKeyIds.contains(keyId), "Created key $keyId not found in list")
        }
    }

    @Test
    fun `SSE connections handle concurrent clients correctly`() {
        val sseConnections = 5 // Fewer for SSE due to resource intensity
        val apiKeys = mutableListOf<String>()
        val sessionIds = mutableListOf<String>()

        // Setup API keys and sessions
        repeat(sseConnections) { i ->
            val keyResponse = createApiKey("SSE Test Key $i")
            val apiKey = keyResponse.get("apiKey").asText()
            apiKeys.add(apiKey)

            val sessionResponse = createMcpSession(apiKey)
            sessionIds.add(sessionResponse.get("sessionId").asText())
        }

        val connectionResults = mutableListOf<io.micronaut.http.HttpResponse<String>>()
        val errors = mutableListOf<Exception>()
        val latch = CountDownLatch(sseConnections)

        // Establish SSE connections concurrently
        repeat(sseConnections) { i ->
            executor.submit {
                try {
                    val sseResponse = establishSseConnection(apiKeys[i], sessionIds[i])
                    synchronized(connectionResults) {
                        connectionResults.add(sseResponse)
                    }
                } catch (e: Exception) {
                    synchronized(errors) {
                        errors.add(e)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "SSE connections took too long")

        // Most connections should succeed
        assertTrue(connectionResults.size >= sseConnections * 0.8,
            "Too many SSE connections failed. Success: ${connectionResults.size}, Errors: ${errors.size}")

        connectionResults.forEach { response ->
            assertEquals(HttpStatus.OK, response.status)
            assertTrue(response.body()!!.contains("event: mcp-message"))
        }

        // Cleanup
        sessionIds.forEachIndexed { index, sessionId ->
            closeSession(apiKeys[index], sessionId)
        }
    }

    @Test
    fun `session cleanup works correctly under concurrent load`() {
        val sessionCount = 12
        val apiKeys = mutableListOf<String>()
        val sessionIds = mutableListOf<String>()

        // Create sessions
        repeat(sessionCount) { i ->
            val keyResponse = createApiKey("Cleanup Test Key $i")
            val apiKey = keyResponse.get("apiKey").asText()
            apiKeys.add(apiKey)

            val sessionResponse = createMcpSession(apiKey)
            sessionIds.add(sessionResponse.get("sessionId").asText())
        }

        // Use sessions concurrently
        val usageLatch = CountDownLatch(sessionCount)
        repeat(sessionCount) { i ->
            executor.submit {
                try {
                    // Multiple operations per session
                    repeat(3) {
                        callTool(apiKeys[i], "get_requirements", mapOf("limit" to 2))
                        Thread.sleep(100) // Small delay
                    }
                } catch (e: Exception) {
                    // Ignore errors for this test
                } finally {
                    usageLatch.countDown()
                }
            }
        }

        assertTrue(usageLatch.await(20, TimeUnit.SECONDS), "Session usage took too long")

        // Cleanup sessions concurrently
        val cleanupLatch = CountDownLatch(sessionCount)
        val cleanupResults = AtomicInteger(0)

        repeat(sessionCount) { i ->
            executor.submit {
                try {
                    val deleteResponse = closeSession(apiKeys[i], sessionIds[i])
                    if (deleteResponse.status == HttpStatus.NO_CONTENT) {
                        cleanupResults.incrementAndGet()
                    }
                } catch (e: Exception) {
                    // Some may fail due to concurrent access
                } finally {
                    cleanupLatch.countDown()
                }
            }
        }

        assertTrue(cleanupLatch.await(15, TimeUnit.SECONDS), "Session cleanup took too long")

        // Most sessions should be cleaned up successfully
        assertTrue(cleanupResults.get() >= sessionCount * 0.8,
            "Too many session cleanups failed: ${cleanupResults.get()}/${sessionCount}")
    }

    @Test
    fun `system maintains performance under concurrent load`() {
        val loadTestDuration = 10000L // 10 seconds
        val threadCount = 8
        val operationsPerThread = AtomicInteger(0)
        val errors = AtomicInteger(0)

        // Setup API keys for load test
        val loadTestKeys = mutableListOf<String>()
        repeat(threadCount) { i ->
            val keyResponse = createApiKey("Load Test Key $i")
            loadTestKeys.add(keyResponse.get("apiKey").asText())
        }

        val startTime = System.currentTimeMillis()
        val stopFlag = AtomicBoolean(false)
        val latch = CountDownLatch(threadCount)

        // Run concurrent load
        repeat(threadCount) { i ->
            executor.submit {
                try {
                    while (!stopFlag.get()) {
                        try {
                            // Mix of operations
                            when ((0..2).random()) {
                                0 -> getCapabilities(loadTestKeys[i])
                                1 -> callTool(loadTestKeys[i], "get_requirements", mapOf("limit" to 3))
                                2 -> callTool(loadTestKeys[i], "search_all",
                                    mapOf("query" to "test", "limit" to 5))
                            }
                            operationsPerThread.incrementAndGet()
                            Thread.sleep(100) // Brief pause between operations
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Run for specified duration
        Thread.sleep(loadTestDuration)
        stopFlag.set(true)

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Load test threads didn't finish")

        val totalOperations = operationsPerThread.get()
        val errorCount = errors.get()
        val elapsedTime = System.currentTimeMillis() - startTime

        println("Load test results:")
        println("Total operations: $totalOperations")
        println("Errors: $errorCount")
        println("Time: ${elapsedTime}ms")
        println("Operations/second: ${totalOperations * 1000L / elapsedTime}")

        // Performance assertions
        assertTrue(totalOperations > 50, "Too few operations completed: $totalOperations")
        assertTrue(errorCount < totalOperations * 0.1, "Too many errors: $errorCount/$totalOperations")
        assertTrue(totalOperations * 1000L / elapsedTime > 5, "Too low throughput")
    }

    // Helper methods

    private fun createApiKey(name: String): JsonNode {
        val requestBody = mapOf(
            "name" to name,
            "permissions" to listOf("REQUIREMENTS_READ", "ASSESSMENTS_READ")
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
                "name" to "Concurrency Test Client",
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

    private fun callTool(apiKey: String, toolName: String, arguments: Map<String, Any>): io.micronaut.http.HttpResponse<JsonNode> {
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "concurrency-test-${System.currentTimeMillis()}-${Thread.currentThread().id}",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to toolName,
                "arguments" to arguments
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        return client.toBlocking().exchange(request, JsonNode::class.java)
    }

    private fun establishSseConnection(apiKey: String, sessionId: String): io.micronaut.http.HttpResponse<String> {
        val request = HttpRequest.GET<String>("/api/mcp/sse/$sessionId")
            .header("X-MCP-API-Key", apiKey)
            .header("Accept", "text/event-stream")

        return client.toBlocking().exchange(request, String::class.java)
    }

    private fun closeSession(apiKey: String, sessionId: String): io.micronaut.http.HttpResponse<String> {
        val request = HttpRequest.DELETE<Any>("/api/mcp/session/$sessionId")
            .header("X-MCP-API-Key", apiKey)

        return client.toBlocking().exchange(request, String::class.java)
    }

    private fun listApiKeys(): io.micronaut.http.HttpResponse<JsonNode> {
        val request = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")
            .header("Authorization", "Bearer $jwtToken")

        return client.toBlocking().exchange(request, JsonNode::class.java)
    }
}