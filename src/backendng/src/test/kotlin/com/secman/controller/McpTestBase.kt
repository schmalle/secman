package com.secman.controller

import com.fasterxml.jackson.databind.JsonNode
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.BeforeEach
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Base class for MCP tests that provides common setup and utilities.
 * Creates test API keys before tests run.
 */
@MicronautTest
abstract class McpTestBase {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    // Test API keys created during setup
    var testApiKeyFull: String = ""
    var testApiKeyReadOnly: String = ""
    var testApiKeyId: String = ""
    var testApiKeyIdReadOnly: String = ""

    @BeforeEach
    fun setupApiKeys() {
        // Create a full-permission API key for tests
        val fullPermissionsKey = createTestApiKey(
            name = "Full Test Key ${System.currentTimeMillis()}",
            permissions = listOf(
                "REQUIREMENTS_READ",
                "REQUIREMENTS_WRITE",
                "REQUIREMENTS_DELETE",
                "ASSESSMENTS_READ",
                "ASSESSMENTS_WRITE",
                "ASSESSMENTS_EXECUTE",
                "FILES_READ",
                "TAGS_READ",
                "TRANSLATION_USE"
            )
        )
        testApiKeyFull = "${fullPermissionsKey.keyId}:${fullPermissionsKey.apiKey}"
        testApiKeyId = fullPermissionsKey.keyId

        // Create a read-only API key for permission tests
        val readOnlyKey = createTestApiKey(
            name = "ReadOnly Test Key ${System.currentTimeMillis()}",
            permissions = listOf(
                "REQUIREMENTS_READ",
                "ASSESSMENTS_READ",
                "FILES_READ"
            )
        )
        testApiKeyReadOnly = "${readOnlyKey.keyId}:${readOnlyKey.apiKey}"
        testApiKeyIdReadOnly = readOnlyKey.keyId
    }

    data class TestApiKey(
        val keyId: String,
        val apiKey: String,
        val name: String
    )

    private fun createTestApiKey(name: String, permissions: List<String>): TestApiKey {
        val requestBody = mapOf(
            "name" to name,
            "permissions" to permissions,
            "expiresAt" to LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )

        val request = HttpRequest.POST("/api/mcp/admin/api-keys", requestBody)
            .header("Authorization", "Bearer test-jwt-token")
            .header("Content-Type", "application/json")

        val response = client.toBlocking().exchange(request, JsonNode::class.java)
        if (response.status != HttpStatus.CREATED) {
            throw RuntimeException("Failed to create test API key: ${response.status}")
        }

        val body = response.body()!!
        return TestApiKey(
            keyId = body.get("keyId").asText(),
            apiKey = body.get("apiKey").asText(),
            name = body.get("name").asText()
        )
    }

    /**
     * Helper method to create API key header for requests
     */
    protected fun apiKeyHeader(apiKey: String = testApiKeyFull): String = apiKey

    /**
     * Helper method to create GET request with API key authentication
     */
    protected fun authenticatedGetRequest(path: String, apiKey: String = testApiKeyFull): HttpRequest<Any> {
        return HttpRequest.GET<Any>(path).header("X-MCP-API-Key", apiKey)
    }

    /**
     * Helper method to create POST request with API key and body
     */
    protected fun authenticatedPostRequest(path: String, body: Any, apiKey: String = testApiKeyFull): HttpRequest<Any> {
        return HttpRequest.POST(path, body)
            .header("X-MCP-API-Key", apiKey)
            .header("Content-Type", "application/json")
    }
}