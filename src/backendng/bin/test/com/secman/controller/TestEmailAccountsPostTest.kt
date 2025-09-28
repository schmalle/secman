package com.secman.controller

import com.secman.domain.enums.EmailProvider
import com.secman.domain.enums.TestAccountStatus
import com.secman.dto.TestErrorResponse
import io.micronaut.http.HttpRequest
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@MicronautTest
class TestEmailAccountsPostTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `POST api test-email-accounts should create new test account`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val requestBody = TestEmailAccountRequest(
            name = "Gmail Test Account",
            emailAddress = "test@gmail.com",
            provider = EmailProvider.GMAIL,
            credentials = mapOf(
                "username" to "test@gmail.com",
                "password" to "app-specific-password"
            )
        )

        // Act
        val request = HttpRequest.POST("/api/test-email-accounts", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, TestEmailAccountDto::class.java)

        // Assert
        assertEquals(HttpStatus.CREATED, response.status)
        assertNotNull(response.body())

        val createdAccount = response.body()!!
        assertNotNull(createdAccount.id)
        assertEquals(requestBody.name, createdAccount.name)
        assertEquals(requestBody.emailAddress, createdAccount.emailAddress)
        assertEquals(requestBody.provider, createdAccount.provider)
        assertEquals(TestAccountStatus.VERIFICATION_PENDING, createdAccount.status)
        assertNotNull(createdAccount.createdAt)
    }

    @Test
    fun `POST api test-email-accounts should require authentication`() {
        // Arrange
        val requestBody = TestEmailAccountRequest(
            name = "Test Account",
            emailAddress = "test@example.com",
            provider = EmailProvider.GMAIL,
            credentials = mapOf("username" to "test", "password" to "pass")
        )

        // Act
        val request = HttpRequest.POST("/api/test-email-accounts", requestBody)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
    }

    @Test
    fun `POST api test-email-accounts should validate required fields`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val invalidRequestBody = TestEmailAccountRequest(
            name = "", // Invalid empty name
            emailAddress = "invalid-email", // Invalid email format
            provider = EmailProvider.GMAIL,
            credentials = emptyMap() // Invalid empty credentials
        )

        // Act
        val request = HttpRequest.POST("/api/test-email-accounts", invalidRequestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, TestErrorResponse::class.java)

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        assertNotNull(response.body())
        assertNotNull(response.body()!!.error)
    }

    @Test
    fun `POST api test-email-accounts should validate email format`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val requestBody = TestEmailAccountRequest(
            name = "Valid Name",
            emailAddress = "not-an-email", // Invalid format
            provider = EmailProvider.GMAIL,
            credentials = mapOf("username" to "test", "password" to "pass")
        )

        // Act
        val request = HttpRequest.POST("/api/test-email-accounts", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, TestErrorResponse::class.java)

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        assertNotNull(response.body())
    }

    @Test
    fun `POST api test-email-accounts should handle different providers`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val requestBody = TestEmailAccountRequest(
            name = "Outlook Test Account",
            emailAddress = "test@outlook.com",
            provider = EmailProvider.OUTLOOK,
            credentials = mapOf(
                "username" to "test@outlook.com",
                "password" to "outlook-password"
            )
        )

        // Act
        val request = HttpRequest.POST("/api/test-email-accounts", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, TestEmailAccountDto::class.java)

        // Assert
        assertEquals(HttpStatus.CREATED, response.status)
        assertNotNull(response.body())
        assertEquals(EmailProvider.OUTLOOK, response.body()!!.provider)
    }

    @Test
    fun `POST api test-email-accounts should handle custom SMTP provider`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val requestBody = TestEmailAccountRequest(
            name = "Custom SMTP Account",
            emailAddress = "test@custom.com",
            provider = EmailProvider.SMTP_CUSTOM,
            credentials = mapOf(
                "host" to "smtp.custom.com",
                "port" to "587",
                "username" to "test@custom.com",
                "password" to "custom-password",
                "tls" to "true"
            )
        )

        // Act
        val request = HttpRequest.POST("/api/test-email-accounts", requestBody)
            .header("Authorization", authToken)
            .contentType(MediaType.APPLICATION_JSON)

        val response = client.toBlocking().exchange(request, TestEmailAccountDto::class.java)

        // Assert
        assertEquals(HttpStatus.CREATED, response.status)
        assertNotNull(response.body())
        assertEquals(EmailProvider.SMTP_CUSTOM, response.body()!!.provider)
    }
}

// Request DTO matching OpenAPI contract
@Serdeable
data class TestEmailAccountRequest(
    val name: String,
    val emailAddress: String,
    val provider: EmailProvider,
    val credentials: Map<String, Any>
)