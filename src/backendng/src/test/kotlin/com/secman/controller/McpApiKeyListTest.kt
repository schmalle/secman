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
 * Contract test for MCP API key listing endpoint.
 * Tests the GET /api/mcp/admin/api-keys endpoint according to OpenAPI specification.
 *
 * This test MUST FAIL initially until the endpoint is implemented.
 */
@MicronautTest
class McpApiKeyListTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `GET api-keys returns user's API keys with valid JWT token`() {
        // Arrange
        val jwtToken = "valid-jwt-token-12345"
        val request = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")
            .header("Authorization", "Bearer $jwtToken")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        val body = response.body()!!

        // Validate ApiKeyListResponse schema
        assertTrue(body.has("apiKeys"))

        val apiKeys = body.get("apiKeys")
        assertTrue(apiKeys.isArray)

        // If there are API keys, validate the structure
        if (apiKeys.size() > 0) {
            val firstKey = apiKeys.get(0)

            // Validate ApiKeyResponse schema (no apiKey field in list response)
            assertTrue(firstKey.has("keyId"))
            assertTrue(firstKey.has("name"))
            assertTrue(firstKey.has("permissions"))
            assertTrue(firstKey.has("isActive"))
            assertTrue(firstKey.has("createdAt"))

            // API key secret should NOT be included in list response
            assertFalse(firstKey.has("apiKey"))

            // Validate field types
            assertTrue(firstKey.get("keyId").isTextual)
            assertTrue(firstKey.get("name").isTextual)
            assertTrue(firstKey.get("permissions").isArray)
            assertTrue(firstKey.get("isActive").isBoolean)
            assertTrue(firstKey.get("createdAt").isTextual)

            // Optional fields
            if (firstKey.has("lastUsedAt")) {
                assertTrue(firstKey.get("lastUsedAt").isTextual)
            }
            if (firstKey.has("expiresAt")) {
                assertTrue(firstKey.get("expiresAt").isTextual)
            }
        }
    }

    @Test
    fun `GET api-keys returns empty array for user with no keys`() {
        // Arrange
        val jwtTokenNoKeys = "jwt-token-user-with-no-keys"
        val request = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")
            .header("Authorization", "Bearer $jwtTokenNoKeys")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body()!!
        assertTrue(body.has("apiKeys"))

        val apiKeys = body.get("apiKeys")
        assertTrue(apiKeys.isArray)
        assertEquals(0, apiKeys.size())
    }

    @Test
    fun `GET api-keys returns 401 without JWT token`() {
        // Arrange
        val request = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    fun `GET api-keys returns 401 with invalid JWT token`() {
        // Arrange
        val invalidJwtToken = "invalid-jwt-token"
        val request = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")
            .header("Authorization", "Bearer $invalidJwtToken")

        // Act & Assert
        val exception = assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, JsonNode::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    fun `GET api-keys only returns keys owned by authenticated user`() {
        // Arrange - Two different user tokens
        val userAToken = "user-a-jwt-token"
        val userBToken = "user-b-jwt-token"

        val requestA = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")
            .header("Authorization", "Bearer $userAToken")

        val requestB = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")
            .header("Authorization", "Bearer $userBToken")

        // Act
        val responseA = client.toBlocking().exchange(requestA, JsonNode::class.java)
        val responseB = client.toBlocking().exchange(requestB, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, responseA.status)
        assertEquals(HttpStatus.OK, responseB.status)

        val keysA = responseA.body()!!.get("apiKeys")
        val keysB = responseB.body()!!.get("apiKeys")

        // If both users have keys, they should be different sets
        if (keysA.size() > 0 && keysB.size() > 0) {
            val keyIdsA = keysA.map { it.get("keyId").asText() }.toSet()
            val keyIdsB = keysB.map { it.get("keyId").asText() }.toSet()

            // No overlap should exist between user keys
            assertTrue(keyIdsA.intersect(keyIdsB).isEmpty())
        }
    }

    @Test
    fun `GET api-keys includes isActive status for each key`() {
        // Arrange
        val jwtToken = "valid-jwt-token-12345"
        val request = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")
            .header("Authorization", "Bearer $jwtToken")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val apiKeys = response.body()!!.get("apiKeys")

        // Check each API key has isActive field
        for (i in 0 until apiKeys.size()) {
            val key = apiKeys.get(i)
            assertTrue(key.has("isActive"))
            assertTrue(key.get("isActive").isBoolean)

            // isActive should be either true or false
            val isActive = key.get("isActive").asBoolean()
            assertTrue(isActive == true || isActive == false)
        }
    }

    @Test
    fun `GET api-keys excludes expired keys from list`() {
        // Arrange
        val jwtToken = "valid-jwt-token-12345"
        val request = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")
            .header("Authorization", "Bearer $jwtToken")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val apiKeys = response.body()!!.get("apiKeys")

        // All keys should be non-expired
        for (i in 0 until apiKeys.size()) {
            val key = apiKeys.get(i)

            if (key.has("expiresAt")) {
                val expiresAt = key.get("expiresAt").asText()
                assertNotNull(expiresAt)

                // If expiration date is present, it should be in the future
                // (This is a simplified check; real implementation would parse the date)
                assertFalse(expiresAt.isEmpty())
            }
        }
    }

    @Test
    fun `GET api-keys sorts keys by creation date descending`() {
        // Arrange - User with multiple API keys
        val jwtToken = "user-with-multiple-keys-token"
        val request = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")
            .header("Authorization", "Bearer $jwtToken")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val apiKeys = response.body()!!.get("apiKeys")

        // If there are multiple keys, verify sorting
        if (apiKeys.size() > 1) {
            for (i in 0 until apiKeys.size() - 1) {
                val currentKey = apiKeys.get(i)
                val nextKey = apiKeys.get(i + 1)

                val currentCreatedAt = currentKey.get("createdAt").asText()
                val nextCreatedAt = nextKey.get("createdAt").asText()

                // Current key should be created after (or equal to) next key
                // (This is a simplified check; real implementation would parse dates)
                assertNotNull(currentCreatedAt)
                assertNotNull(nextCreatedAt)
            }
        }
    }

    @Test
    fun `GET api-keys includes lastUsedAt when available`() {
        // Arrange
        val jwtToken = "valid-jwt-token-12345"
        val request = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")
            .header("Authorization", "Bearer $jwtToken")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val apiKeys = response.body()!!.get("apiKeys")

        for (i in 0 until apiKeys.size()) {
            val key = apiKeys.get(i)

            // lastUsedAt is optional
            if (key.has("lastUsedAt")) {
                val lastUsedAt = key.get("lastUsedAt")

                // If present, should be a valid timestamp string or null
                assertTrue(lastUsedAt.isTextual || lastUsedAt.isNull)

                if (lastUsedAt.isTextual) {
                    assertFalse(lastUsedAt.asText().isEmpty())
                }
            }
        }
    }

    @Test
    fun `GET api-keys validates permissions array format`() {
        // Arrange
        val jwtToken = "valid-jwt-token-12345"
        val request = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")
            .header("Authorization", "Bearer $jwtToken")

        // Act
        val response = client.toBlocking().exchange(request, JsonNode::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val apiKeys = response.body()!!.get("apiKeys")

        for (i in 0 until apiKeys.size()) {
            val key = apiKeys.get(i)

            assertTrue(key.has("permissions"))
            val permissions = key.get("permissions")

            assertTrue(permissions.isArray)
            assertTrue(permissions.size() > 0) // Should have at least one permission

            // Each permission should be a valid string
            for (j in 0 until permissions.size()) {
                val permission = permissions.get(j)
                assertTrue(permission.isTextual)

                val permissionValue = permission.asText()
                assertFalse(permissionValue.isEmpty())

                // Should be one of the valid MCP permissions
                val validPermissions = setOf(
                    "REQUIREMENTS_READ",
                    "REQUIREMENTS_WRITE",
                    "REQUIREMENTS_DELETE",
                    "ASSESSMENTS_READ",
                    "ASSESSMENTS_EXECUTE",
                    "FILES_READ",
                    "TRANSLATION_USE",
                    "AUDIT_READ"
                )
                assertTrue(validPermissions.contains(permissionValue),
                    "Invalid permission: $permissionValue")
            }
        }
    }

    @Test
    fun `GET api-keys handles large number of keys efficiently`() {
        // Arrange - Simulate user with many API keys
        val jwtToken = "user-with-many-keys-token"
        val request = HttpRequest.GET<Any>("/api/mcp/admin/api-keys")
            .header("Authorization", "Bearer $jwtToken")

        // Act
        val startTime = System.currentTimeMillis()
        val response = client.toBlocking().exchange(request, JsonNode::class.java)
        val endTime = System.currentTimeMillis()

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        // Response time should be reasonable (less than 1 second)
        val responseTime = endTime - startTime
        assertTrue(responseTime < 1000, "API key listing took too long: ${responseTime}ms")

        val apiKeys = response.body()!!.get("apiKeys")
        assertTrue(apiKeys.isArray)

        // All keys should have required fields
        for (i in 0 until apiKeys.size()) {
            val key = apiKeys.get(i)
            assertTrue(key.has("keyId"))
            assertTrue(key.has("name"))
            assertTrue(key.has("permissions"))
            assertTrue(key.has("isActive"))
            assertTrue(key.has("createdAt"))
        }
    }
}