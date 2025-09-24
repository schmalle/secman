package com.secman.controller

import com.secman.domain.enums.EmailStatus
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

@MicronautTest
class NotificationLogsTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `GET api notifications logs should return paginated logs`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"

        // Act
        val request = HttpRequest.GET<Any>("/api/notifications/logs")
            .header("Authorization", authToken)

        val response = client.toBlocking().exchange(request, NotificationLogsResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        val responseBody = response.body()!!
        assertNotNull(responseBody.logs)
        assertNotNull(responseBody.total)
        assertNotNull(responseBody.limit)
        assertNotNull(responseBody.offset)
        assertEquals(50, responseBody.limit) // Default limit
        assertEquals(0, responseBody.offset) // Default offset
    }

    @Test
    fun `GET api notifications logs should filter by risk assessment ID`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val riskAssessmentId = 123L

        // Act
        val request = HttpRequest.GET<Any>("/api/notifications/logs?riskAssessmentId=$riskAssessmentId")
            .header("Authorization", authToken)

        val response = client.toBlocking().exchange(request, NotificationLogsResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        // Verify all logs have the correct risk assessment ID
        val logs = response.body()!!.logs
        logs.forEach { log ->
            assertEquals(riskAssessmentId, log.riskAssessmentId)
        }
    }

    @Test
    fun `GET api notifications logs should filter by status`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val status = EmailStatus.SENT

        // Act
        val request = HttpRequest.GET<Any>("/api/notifications/logs?status=$status")
            .header("Authorization", authToken)

        val response = client.toBlocking().exchange(request, NotificationLogsResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        // Verify all logs have the correct status
        val logs = response.body()!!.logs
        logs.forEach { log ->
            assertEquals(status, log.status)
        }
    }

    @Test
    fun `GET api notifications logs should respect pagination parameters`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val limit = 10
        val offset = 20

        // Act
        val request = HttpRequest.GET<Any>("/api/notifications/logs?limit=$limit&offset=$offset")
            .header("Authorization", authToken)

        val response = client.toBlocking().exchange(request, NotificationLogsResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        val responseBody = response.body()!!
        assertEquals(limit, responseBody.limit)
        assertEquals(offset, responseBody.offset)
        assertTrue(responseBody.logs.size <= limit)
    }

    @Test
    fun `GET api notifications logs should require authentication`() {
        // Act
        val request = HttpRequest.GET<Any>("/api/notifications/logs")

        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
    }

    @Test
    fun `GET api notifications logs should validate pagination limits`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val invalidLimit = 500 // Exceeds maximum of 200

        // Act
        val request = HttpRequest.GET<Any>("/api/notifications/logs?limit=$invalidLimit")
            .header("Authorization", authToken)

        val response = client.toBlocking().exchange(request, NotificationLogsResponse::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        // Should cap at maximum limit of 200
        assertTrue(response.body()!!.limit <= 200)
    }
}

// Response DTOs matching OpenAPI contract
data class NotificationLogsResponse(
    val logs: List<EmailNotificationLogDto>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

data class EmailNotificationLogDto(
    val id: Long,
    val riskAssessmentId: Long,
    val emailConfigId: Long,
    val recipientEmail: String,
    val subject: String,
    val status: EmailStatus,
    val errorMessage: String?,
    val attempts: Int,
    val sentAt: LocalDateTime?,
    val nextRetryAt: LocalDateTime?,
    val createdAt: LocalDateTime
)