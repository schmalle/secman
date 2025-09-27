package com.secman.controller

import com.secman.domain.RiskAssessmentNotificationConfig
import com.secman.repository.RiskAssessmentNotificationConfigRepository
import io.micronaut.http.HttpRequest
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.every
import io.mockk.mockk
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

@MicronautTest
class NotificationConfigTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `GET api notifications configs should return list of configs`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"

        // Act
        val request = HttpRequest.GET<Any>("/api/notifications/configs")
            .header("Authorization", authToken)

        val response = client.toBlocking().exchange(request, Array<NotificationConfig>::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
        assertTrue(response.body()!!.isNotEmpty())

        // Verify response structure matches OpenAPI contract
        val config = response.body()!![0]
        assertNotNull(config.id)
        assertNotNull(config.name)
        assertNotNull(config.recipientEmails)
        assertTrue(config.recipientEmails.isNotEmpty())
        assertNotNull(config.isActive)
        assertNotNull(config.createdAt)
    }

    @Test
    fun `GET api notifications configs should require authentication`() {
        // Act
        val request = HttpRequest.GET<Any>("/api/notifications/configs")

        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
    }

    @Test
    fun `GET api notifications configs should handle empty list`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"

        // Act
        val request = HttpRequest.GET<Any>("/api/notifications/configs")
            .header("Authorization", authToken)

        val response = client.toBlocking().exchange(request, Array<NotificationConfig>::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
        // Empty list is valid response
    }
}

// DTO classes matching OpenAPI contract
@Serdeable
data class NotificationConfig(
    val id: Long,
    val name: String,
    val recipientEmails: List<String>,
    val conditions: Map<String, Any>?,
    val isActive: Boolean,
    val createdAt: LocalDateTime
)