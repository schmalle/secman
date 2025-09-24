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
class TestEmailAccountsTestTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `POST api test-email-accounts {id} test should execute email test`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val accountId = 1L
        val requestBody = TestEmailRequest(
            subject = "SecMan Test Email",
            content = "This is a test email to verify the email account functionality.",
            useHtml = false
        )

        // Act
        val request = HttpRequest.POST("/api/test-email-accounts/$accountId/test", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, TestResult::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        val result = response.body()!!
        assertNotNull(result.success)
        assertNotNull(result.testType)
        assertNotNull(result.timestamp)

        if (result.success) {
            assertNotNull(result.messageId)
            assertNull(result.error)
        } else {
            assertNotNull(result.error)
        }
    }

    @Test
    fun `POST api test-email-accounts {id} test should handle HTML content`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val accountId = 1L
        val requestBody = TestEmailRequest(
            subject = "HTML Test Email",
            content = "<h1>Test Email</h1><p>This is an <strong>HTML</strong> test email.</p>",
            useHtml = true
        )

        // Act
        val request = HttpRequest.POST("/api/test-email-accounts/$accountId/test", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, TestResult::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
    }

    @Test
    fun `POST api test-email-accounts {id} test should require authentication`() {
        // Arrange
        val accountId = 1L
        val requestBody = TestEmailRequest(
            subject = "Test",
            content = "Test content",
            useHtml = false
        )

        // Act
        val request = HttpRequest.POST("/api/test-email-accounts/$accountId/test", requestBody)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
    }

    @Test
    fun `POST api test-email-accounts {id} test should validate required fields`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val accountId = 1L
        val invalidRequestBody = TestEmailRequest(
            subject = "", // Invalid empty subject
            content = "", // Invalid empty content
            useHtml = false
        )

        // Act
        val request = HttpRequest.POST("/api/test-email-accounts/$accountId/test", invalidRequestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, ErrorResponse::class.java)

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        assertNotNull(response.body())
    }

    @Test
    fun `POST api test-email-accounts {id} test should handle non-existent account`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val nonExistentAccountId = 99999L
        val requestBody = TestEmailRequest(
            subject = "Test Subject",
            content = "Test content",
            useHtml = false
        )

        // Act
        val request = HttpRequest.POST("/api/test-email-accounts/$nonExistentAccountId/test", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, ErrorResponse::class.java)

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.status)
        assertNotNull(response.body())
    }

    @Test
    fun `POST api test-email-accounts {id} test should handle account in failed state`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val failedAccountId = 2L // Assume this account is in FAILED state
        val requestBody = TestEmailRequest(
            subject = "Test Subject",
            content = "Test content",
            useHtml = false
        )

        // Act
        val request = HttpRequest.POST("/api/test-email-accounts/$failedAccountId/test", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, ErrorResponse::class.java)

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        assertNotNull(response.body())
        assertTrue(response.body()!!.message.contains("cannot be tested"))
    }

    @Test
    fun `POST api test-email-accounts {id} test should validate subject length`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val accountId = 1L
        val requestBody = TestEmailRequest(
            subject = "x".repeat(201), // Exceeds 200 character limit
            content = "Test content",
            useHtml = false
        )

        // Act
        val request = HttpRequest.POST("/api/test-email-accounts/$accountId/test", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, ErrorResponse::class.java)

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        assertNotNull(response.body())
    }
}

// Request DTO matching OpenAPI contract
data class TestEmailRequest(
    val subject: String,
    val content: String,
    val useHtml: Boolean = false
)

// Response DTO matching OpenAPI contract
data class TestResult(
    val success: Boolean,
    val messageId: String?,
    val error: String?,
    val testType: String,
    val timestamp: LocalDateTime,
    val details: Map<String, Any>?
)