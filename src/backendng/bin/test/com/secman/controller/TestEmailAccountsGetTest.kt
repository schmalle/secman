package com.secman.controller

import com.secman.domain.enums.EmailProvider
import com.secman.domain.enums.TestAccountStatus
import com.secman.dto.TestErrorResponse
import io.micronaut.http.HttpRequest
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

@MicronautTest
class TestEmailAccountsGetTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `GET api test-email-accounts should return list of accounts`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"

        // Act
        val request = HttpRequest.GET<Any>("/api/test-email-accounts")
            .header("Authorization", authToken)

        val response = client.toBlocking().exchange(request, Array<TestEmailAccountDto>::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        // Verify response structure matches OpenAPI contract
        if (response.body()!!.isNotEmpty()) {
            val account = response.body()!![0]
            assertNotNull(account.id)
            assertNotNull(account.name)
            assertNotNull(account.emailAddress)
            assertNotNull(account.provider)
            assertNotNull(account.status)
            assertNotNull(account.createdAt)
        }
    }

    @Test
    fun `GET api test-email-accounts should filter by status`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val status = TestAccountStatus.ACTIVE

        // Act
        val request = HttpRequest.GET<Any>("/api/test-email-accounts?status=$status")
            .header("Authorization", authToken)

        val response = client.toBlocking().exchange(request, Array<TestEmailAccountDto>::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        // Verify all accounts have the correct status
        response.body()!!.forEach { account ->
            assertEquals(status, account.status)
        }
    }

    @Test
    fun `GET api test-email-accounts should filter by provider`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"
        val provider = EmailProvider.GMAIL

        // Act
        val request = HttpRequest.GET<Any>("/api/test-email-accounts?provider=$provider")
            .header("Authorization", authToken)

        val response = client.toBlocking().exchange(request, Array<TestEmailAccountDto>::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())

        // Verify all accounts have the correct provider
        response.body()!!.forEach { account ->
            assertEquals(provider, account.provider)
        }
    }

    @Test
    fun `GET api test-email-accounts should require authentication`() {
        // Act
        val request = HttpRequest.GET<Any>("/api/test-email-accounts")

        val response = client.toBlocking().exchange(request, String::class.java)

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
    }

    @Test
    fun `GET api test-email-accounts should handle empty list`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"

        // Act
        val request = HttpRequest.GET<Any>("/api/test-email-accounts")
            .header("Authorization", authToken)

        val response = client.toBlocking().exchange(request, Array<TestEmailAccountDto>::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
        // Empty array is valid response
    }

    @Test
    fun `GET api test-email-accounts should validate enum parameters`() {
        // Arrange
        val authToken = "Bearer test-jwt-token"

        // Act
        val request = HttpRequest.GET<Any>("/api/test-email-accounts?status=INVALID_STATUS")
            .header("Authorization", authToken)

        val response = client.toBlocking().exchange(request, TestErrorResponse::class.java)

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.status)
        assertNotNull(response.body())
    }
}

// DTO matching OpenAPI contract
@Serdeable
data class TestEmailAccountDto(
    val id: Long,
    val name: String,
    val emailAddress: String,
    val provider: EmailProvider,
    val status: TestAccountStatus,
    val lastTestResult: Map<String, Any>?,
    val lastTestedAt: LocalDateTime?,
    val createdAt: LocalDateTime
)