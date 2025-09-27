package com.secman.controller

import com.secman.dto.TestErrorResponse
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@MicronautTest
class ManualNotificationTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `POST api notifications send should queue manual notification`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val requestBody = ManualNotificationRequest(
            riskAssessmentId = 1L,
            recipientEmails = listOf("manager@company.com", "security@company.com"),
            subject = "Critical Risk Assessment Review Required",
            message = "A high-risk assessment requires your immediate attention.",
            useHtml = false
        )

        // Act
        val request = HttpRequest.POST("/api/notifications/send", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, ManualNotificationResponse::class.java)

        // Assert
        assertEquals(HttpStatus.ACCEPTED, response.status)
        assertNotNull(response.body())

        val responseBody = response.body()!!
        assertEquals("Notification queued for sending", responseBody.message)
        assertNotNull(responseBody.notificationId)
        assertTrue(responseBody.notificationId > 0)
    }

    @Test
    fun `POST api notifications send should require authentication`() {
        // Arrange
        val requestBody = ManualNotificationRequest(
            riskAssessmentId = 1L,
            recipientEmails = listOf("test@example.com"),
            subject = "Test",
            message = "Test message",
            useHtml = false
        )

        // Act
        val request = HttpRequest.POST("/api/notifications/send", requestBody)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
    }

    @Test
    fun `POST api notifications send should validate required fields`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val invalidRequestBody = ManualNotificationRequest(
            riskAssessmentId = 0L, // Invalid ID
            recipientEmails = emptyList(), // Invalid empty list
            subject = "", // Invalid empty subject
            message = "", // Invalid empty message
            useHtml = false
        )

        // Act
        val request = HttpRequest.POST("/api/notifications/send", invalidRequestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, TestErrorResponse::class.java)

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        assertNotNull(response.body())
        assertNotNull(response.body()!!.error)
    }

    @Test
    fun `POST api notifications send should validate email addresses`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val requestBody = ManualNotificationRequest(
            riskAssessmentId = 1L,
            recipientEmails = listOf("invalid-email"), // Invalid email format
            subject = "Test Subject",
            message = "Test message",
            useHtml = false
        )

        // Act
        val request = HttpRequest.POST("/api/notifications/send", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, TestErrorResponse::class.java)

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        assertNotNull(response.body())
    }

    @Test
    fun `POST api notifications send should handle HTML content`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val requestBody = ManualNotificationRequest(
            riskAssessmentId = 1L,
            recipientEmails = listOf("test@example.com"),
            subject = "HTML Test",
            message = "<h1>Test HTML Content</h1><p>This is a test.</p>",
            useHtml = true
        )

        // Act
        val request = HttpRequest.POST("/api/notifications/send", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, ManualNotificationResponse::class.java)

        // Assert
        assertEquals(HttpStatus.ACCEPTED, response.status)
        assertNotNull(response.body())
    }
}

// Request DTO matching OpenAPI contract
@Serdeable
data class ManualNotificationRequest(
    val riskAssessmentId: Long,
    val recipientEmails: List<String>,
    val subject: String,
    val message: String,
    val useHtml: Boolean = false
)

// Response DTO matching OpenAPI contract
@Serdeable
data class ManualNotificationResponse(
    val message: String,
    val notificationId: Long
)