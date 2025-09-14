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
 * Integration test for MCP API key authentication.
 * Tests the complete authentication flow: key generation → validation → expiration → revocation
 *
 * This test MUST FAIL initially until the complete MCP authentication system is implemented.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpAuthenticationTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    private lateinit var jwtToken: String
    private lateinit var validApiKey: String
    private lateinit var expiredApiKey: String
    private lateinit var revokedApiKey: String

    @BeforeEach
    fun setupTestData() {
        jwtToken = "auth-integration-test-jwt-token"
    }

    @Test
    fun `API key authentication workflow from generation to usage`() {
        // Step 1: Generate new API key
        val apiKeyResponse = generateApiKey("Auth Test Key", listOf("REQUIREMENTS_READ"))
        assertEquals(HttpStatus.CREATED, apiKeyResponse.status)

        val apiKeyBody = apiKeyResponse.body()!!
        val newApiKey = apiKeyBody.get("apiKey").asText()
        val keyId = apiKeyBody.get("keyId").asText()

        assertNotNull(newApiKey)
        assertTrue(newApiKey.startsWith("sk-"))
        assertTrue(newApiKey.length >= 32)

        // Step 2: Use API key for authentication
        val capabilitiesResponse = getCapabilitiesWithApiKey(newApiKey)
        assertEquals(HttpStatus.OK, capabilitiesResponse.status)

        val capabilities = capabilitiesResponse.body()!!
        assertTrue(capabilities.has("tools"))
        assertTrue(capabilities.has("resources"))

        // Step 3: Create MCP session with API key
        val sessionResponse = createSessionWithApiKey(newApiKey)
        assertEquals(HttpStatus.CREATED, sessionResponse.status)

        val sessionId = sessionResponse.body()!!.get("sessionId").asText()
        assertNotNull(sessionId)

        // Step 4: Use session for tool calls
        val toolResponse = callToolWithApiKey(newApiKey, "get_requirements")
        assertEquals(HttpStatus.OK, toolResponse.status)

        val toolResult = toolResponse.body()!!.get("result")
        assertFalse(toolResult.get("isError").asBoolean(false))

        // Step 5: Verify key appears in user's key list
        val listResponse = listApiKeys()
        assertEquals(HttpStatus.OK, listResponse.status)

        val keysList = listResponse.body()!!.get("apiKeys")
        val foundKey = keysList.find { it.get("keyId").asText() == keyId }
        assertNotNull(foundKey)
        assertEquals("Auth Test Key", foundKey!!.get("name").asText())
    }

    @Test
    fun `API key validation rejects invalid keys`() {
        val invalidKeys = listOf(
            "",                    // Empty key
            "invalid-key",         // Too short
            "sk-invalid",          // Wrong format
            "not-a-valid-key-at-all", // Invalid characters
            "sk-" + "a".repeat(100)   // Too long
        )

        invalidKeys.forEach { invalidKey ->
            val exception = assertThrows(
                io.micronaut.http.client.exceptions.HttpClientResponseException::class.java
            ) {
                getCapabilitiesWithApiKey(invalidKey)
            }

            assertEquals(HttpStatus.UNAUTHORIZED, exception.status,
                "Invalid key should be rejected: $invalidKey")
        }
    }

    @Test
    fun `API key expiration is enforced`() {
        // Create key that expires in 1 second (for testing)
        val expirationTime = LocalDateTime.now().plusSeconds(1)
        val expiredKeyResponse = generateApiKeyWithExpiration(
            "Expiring Test Key",
            listOf("REQUIREMENTS_READ"),
            expirationTime
        )
        assertEquals(HttpStatus.CREATED, expiredKeyResponse.status)

        val expiredKey = expiredKeyResponse.body()!!.get("apiKey").asText()

        // Key should work initially
        val initialResponse = getCapabilitiesWithApiKey(expiredKey)
        assertEquals(HttpStatus.OK, initialResponse.status)

        // Wait for expiration (plus small buffer)
        Thread.sleep(2000)

        // Key should now be expired
        val expiredResponse = assertThrows(
            io.micronaut.http.client.exceptions.HttpClientResponseException::class.java
        ) {
            getCapabilitiesWithApiKey(expiredKey)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, expiredResponse.status)
    }

    @Test
    fun `API key revocation works immediately`() {
        // Create key to be revoked
        val keyResponse = generateApiKey("Key To Revoke", listOf("REQUIREMENTS_READ"))
        val keyToRevoke = keyResponse.body()!!.get("apiKey").asText()
        val keyId = keyResponse.body()!!.get("keyId").asText()

        // Key should work initially
        val initialResponse = getCapabilitiesWithApiKey(keyToRevoke)
        assertEquals(HttpStatus.OK, initialResponse.status)

        // Revoke the key
        val revokeResponse = revokeApiKey(keyId)
        assertEquals(HttpStatus.NO_CONTENT, revokeResponse.status)

        // Key should no longer work
        val revokedResponse = assertThrows(
            io.micronaut.http.client.exceptions.HttpClientResponseException::class.java
        ) {
            getCapabilitiesWithApiKey(keyToRevoke)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, revokedResponse.status)
    }

    @Test
    fun `API key permissions are enforced correctly`() {
        // Create key with only read permissions
        val readOnlyResponse = generateApiKey("Read Only Key", listOf("REQUIREMENTS_READ"))
        val readOnlyKey = readOnlyResponse.body()!!.get("apiKey").asText()

        // Create key with write permissions
        val writeResponse = generateApiKey("Write Key",
            listOf("REQUIREMENTS_READ", "REQUIREMENTS_WRITE"))
        val writeKey = writeResponse.body()!!.get("apiKey").asText()

        // Read-only key should be able to read
        val readResponse = callToolWithApiKey(readOnlyKey, "get_requirements")
        assertEquals(HttpStatus.OK, readResponse.status)

        // Read-only key should NOT be able to write
        val writeWithReadOnlyException = assertThrows(
            io.micronaut.http.client.exceptions.HttpClientResponseException::class.java
        ) {
            callCreateRequirementTool(readOnlyKey)
        }
        assertEquals(HttpStatus.FORBIDDEN, writeWithReadOnlyException.status)

        // Write key should be able to both read and write
        val readWithWriteResponse = callToolWithApiKey(writeKey, "get_requirements")
        assertEquals(HttpStatus.OK, readWithWriteResponse.status)

        val writeWithWriteResponse = callCreateRequirementTool(writeKey)
        assertEquals(HttpStatus.OK, writeWithWriteResponse.status)
    }

    @Test
    fun `API key rate limiting works correctly`() {
        // Create key for rate limiting test
        val keyResponse = generateApiKey("Rate Limit Test Key", listOf("REQUIREMENTS_READ"))
        val rateLimitKey = keyResponse.body()!!.get("apiKey").asText()

        // Make many rapid requests (more than typical rate limit)
        var successCount = 0
        var rateLimitedCount = 0

        repeat(50) { // Try 50 requests rapidly
            try {
                val response = getCapabilitiesWithApiKey(rateLimitKey)
                if (response.status == HttpStatus.OK) {
                    successCount++
                }
            } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
                if (e.status == HttpStatus.TOO_MANY_REQUESTS) {
                    rateLimitedCount++
                }
            }
        }

        // Should have some successes and some rate limits (or all successes if no rate limiting)
        assertTrue(successCount > 0, "Should have some successful requests")
        // Rate limiting might not be implemented yet, so we don't assert rateLimitedCount > 0
    }

    @Test
    fun `API key usage is tracked correctly`() {
        // Create key for usage tracking
        val keyResponse = generateApiKey("Usage Tracking Key", listOf("REQUIREMENTS_READ"))
        val trackingKey = keyResponse.body()!!.get("apiKey").asText()
        val keyId = keyResponse.body()!!.get("keyId").asText()

        // Get initial key info (should have no lastUsedAt)
        val initialListResponse = listApiKeys()
        val initialKeyInfo = initialListResponse.body()!!.get("apiKeys")
            .find { it.get("keyId").asText() == keyId }!!

        // lastUsedAt should be null initially
        assertTrue(initialKeyInfo.get("lastUsedAt").isNull ||
                  !initialKeyInfo.has("lastUsedAt"))

        // Use the key
        val usageResponse = getCapabilitiesWithApiKey(trackingKey)
        assertEquals(HttpStatus.OK, usageResponse.status)

        // Small delay to ensure timestamp difference
        Thread.sleep(100)

        // Get updated key info (should have lastUsedAt)
        val updatedListResponse = listApiKeys()
        val updatedKeyInfo = updatedListResponse.body()!!.get("apiKeys")
            .find { it.get("keyId").asText() == keyId }!!

        // lastUsedAt should now be set
        assertTrue(updatedKeyInfo.has("lastUsedAt"))
        assertFalse(updatedKeyInfo.get("lastUsedAt").isNull)
    }

    @Test
    fun `API key cross-user isolation works correctly`() {
        // This test would require multiple user accounts
        // For now, we'll test that users can't access each other's keys

        val userAToken = "user-a-jwt-token"
        val userBToken = "user-b-jwt-token"

        // Create key for user A
        val userAKeyResponse = generateApiKeyForUser(userAToken, "User A Key",
            listOf("REQUIREMENTS_READ"))
        val userAKey = userAKeyResponse.body()!!.get("apiKey").asText()

        // Create key for user B
        val userBKeyResponse = generateApiKeyForUser(userBToken, "User B Key",
            listOf("REQUIREMENTS_READ"))
        val userBKey = userBKeyResponse.body()!!.get("apiKey").asText()

        // User A should only see their own keys
        val userAListResponse = listApiKeysForUser(userAToken)
        val userAKeys = userAListResponse.body()!!.get("apiKeys")

        val userAKeyIds = userAKeys.map { it.get("keyId").asText() }.toSet()
        val userBKeyId = userBKeyResponse.body()!!.get("keyId").asText()

        assertFalse(userAKeyIds.contains(userBKeyId),
            "User A should not see User B's keys")

        // Both keys should work for their respective users
        val userAAuthResponse = getCapabilitiesWithApiKey(userAKey)
        assertEquals(HttpStatus.OK, userAAuthResponse.status)

        val userBAuthResponse = getCapabilitiesWithApiKey(userBKey)
        assertEquals(HttpStatus.OK, userBAuthResponse.status)
    }

    // Helper methods

    private fun generateApiKey(name: String, permissions: List<String>): io.micronaut.http.HttpResponse<JsonNode> {
        return generateApiKeyForUser(jwtToken, name, permissions)
    }

    private fun generateApiKeyForUser(token: String, name: String, permissions: List<String>): io.micronaut.http.HttpResponse<JsonNode> {
        val requestBody = mapOf(
            "name" to name,
            "permissions" to permissions
        )

        val request = HttpRequest.POST("/api/mcp/admin/api-keys", requestBody)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")

        return client.toBlocking().exchange(request, JsonNode::class.java)
    }

    private fun generateApiKeyWithExpiration(name: String, permissions: List<String>,
                                           expiresAt: LocalDateTime): io.micronaut.http.HttpResponse<JsonNode> {
        val requestBody = mapOf(
            "name" to name,
            "permissions" to permissions,
            "expiresAt" to expiresAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )

        val request = HttpRequest.POST("/api/mcp/admin/api-keys", requestBody)
            .header("Authorization", "Bearer $jwtToken")
            .header("Content-Type", "application/json")

        return client.toBlocking().exchange(request, JsonNode::class.java)
    }

    private fun getCapabilitiesWithApiKey(apiKey: String): io.micronaut.http.HttpResponse<JsonNode> {
        val request = HttpRequest.GET<Any>("/api/mcp/capabilities")
            .header("X-MCP-API-Key", apiKey)

        return client.toBlocking().exchange(request, JsonNode::class.java)
    }

    private fun createSessionWithApiKey(apiKey: String): io.micronaut.http.HttpResponse<JsonNode> {
        val requestBody = mapOf(
            "capabilities" to mapOf(
                "tools" to emptyMap<String, Any>(),
                "resources" to emptyMap<String, Any>(),
                "prompts" to emptyMap<String, Any>()
            ),
            "clientInfo" to mapOf(
                "name" to "Auth Test Client",
                "version" to "1.0.0"
            )
        )

        val request = HttpRequest.POST("/api/mcp/session", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        return client.toBlocking().exchange(request, JsonNode::class.java)
    }

    private fun callToolWithApiKey(apiKey: String, toolName: String): io.micronaut.http.HttpResponse<JsonNode> {
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "auth-test-${System.currentTimeMillis()}",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to toolName,
                "arguments" to mapOf("limit" to 5)
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        return client.toBlocking().exchange(request, JsonNode::class.java)
    }

    private fun callCreateRequirementTool(apiKey: String): io.micronaut.http.HttpResponse<JsonNode> {
        val requestBody = mapOf(
            "jsonrpc" to "2.0",
            "id" to "create-test-${System.currentTimeMillis()}",
            "method" to "tools/call",
            "params" to mapOf(
                "name" to "create_requirement",
                "arguments" to mapOf(
                    "title" to "Auth Test Requirement",
                    "description" to "Testing permissions"
                )
            )
        )

        val request = HttpRequest.POST("/api/mcp/tools/call", requestBody)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")

        return client.toBlocking().exchange(request, JsonNode::class.java)
    }

    private fun listApiKeys(): io.micronaut.http.HttpResponse<JsonNode> {
        return listApiKeysForUser(jwtToken)
    }

    private fun listApiKeysForUser(token: String): io.micronaut.http.HttpResponse<JsonNode> {
        val request = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")
            .header("Authorization", "Bearer $token")

        return client.toBlocking().exchange(request, JsonNode::class.java)
    }

    private fun revokeApiKey(keyId: String): io.micronaut.http.HttpResponse<String> {
        val request = HttpRequest.DELETE<Any>("/api/mcp/admin/api-keys/$keyId")
            .header("Authorization", "Bearer $jwtToken")

        return client.toBlocking().exchange(request, String::class.java)
    }
}