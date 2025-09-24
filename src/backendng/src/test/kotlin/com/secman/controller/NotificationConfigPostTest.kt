package com.secman.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

@MicronautTest
class NotificationConfigPostTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `POST api notifications configs should create new configuration`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val requestBody = NotificationConfigRequest(
            name = "Security Team",
            recipientEmails = listOf("security@company.com", "admin@company.com"),
            conditions = mapOf("riskLevel" to "HIGH")
        )

        // Act
        val request = HttpRequest.POST("/api/notifications/configs", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, NotificationConfig::class.java)

        // Assert
        assertEquals(HttpStatus.CREATED, response.status)
        assertNotNull(response.body())

        val createdConfig = response.body()!!
        assertNotNull(createdConfig.id)
        assertEquals(requestBody.name, createdConfig.name)
        assertEquals(requestBody.recipientEmails, createdConfig.recipientEmails)
        assertEquals(requestBody.conditions, createdConfig.conditions)
        assertTrue(createdConfig.isActive)
        assertNotNull(createdConfig.createdAt)
    }

    @Test
    fun `POST api notifications configs should require authentication`() {
        // Arrange
        val requestBody = NotificationConfigRequest(
            name = "Test Config",
            recipientEmails = listOf("test@example.com"),
            conditions = null
        )

        // Act
        val request = HttpRequest.POST("/api/notifications/configs", requestBody)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
    }

    @Test
    fun `POST api notifications configs should validate required fields`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val invalidRequestBody = NotificationConfigRequest(
            name = "", // Invalid empty name
            recipientEmails = emptyList(), // Invalid empty emails
            conditions = null
        )

        // Act
        val request = HttpRequest.POST("/api/notifications/configs", invalidRequestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, ErrorResponse::class.java)

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        assertNotNull(response.body())
        assertNotNull(response.body()!!.error)
    }

    @Test
    fun `POST api notifications configs should validate email format`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val requestBody = NotificationConfigRequest(
            name = "Test Config",
            recipientEmails = listOf("invalid-email-format"), // Invalid email
            conditions = null
        )

        // Act
        val request = HttpRequest.POST("/api/notifications/configs", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, ErrorResponse::class.java)

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        assertNotNull(response.body())
        assertNotNull(response.body()!!.error)
    }
}

// Request DTO matching OpenAPI contract
data class NotificationConfigRequest(
    val name: String,
    val recipientEmails: List<String>,
    val conditions: Map<String, Any>?
)

// Error response DTO matching OpenAPI contract
data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: LocalDateTime
)