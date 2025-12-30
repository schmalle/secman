package com.secman.controller

import com.secman.domain.User
import com.secman.repository.UserRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.condition.EnabledIf

/**
 * Integration tests for AuthController.
 * Feature: 056-test-suite (User Story 3 - P3)
 *
 * Tests authentication flows:
 * - Login with valid credentials
 * - Login rejection for invalid credentials
 * - Token validation via status endpoint
 */
@DisplayName("AuthController Tests")
@EnabledIf("com.secman.testutil.DockerAvailable#isDockerAvailable")
class AuthControllerTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    private lateinit var testUser: User

    @Serdeable
    data class LoginRequest(val username: String, val password: String)

    @Serdeable
    data class LoginResponse(
        val id: Long,
        val username: String,
        val email: String,
        val roles: List<String>,
        val token: String
    )

    @Serdeable
    data class StatusResponse(
        val id: Long,
        val username: String,
        val email: String,
        val roles: List<String>
    )

    @BeforeEach
    fun setupTestUser() {
        testUser = userRepository.save(TestDataFactory.createAdminUser(
            username = "auth-test-${System.nanoTime()}",
            email = "auth-test-${System.nanoTime()}@test.com"
        ))
    }

    @Nested
    @DisplayName("Login Tests")
    inner class LoginTests {

        @Test
        @DisplayName("AC-001: Login returns JWT token")
        fun `login_returnsJwtToken`() {
            // Given: Valid credentials
            val request = LoginRequest(testUser.username, TestDataFactory.DEFAULT_PASSWORD)

            // When: POST to login
            val response = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login", request),
                LoginResponse::class.java
            )

            // Then: HTTP 200 with valid response
            assertThat(response.status).isEqualTo(HttpStatus.OK)
            val body = response.body()!!
            assertThat(body.token).isNotBlank()
            assertThat(body.username).isEqualTo(testUser.username)
            assertThat(body.email).isEqualTo(testUser.email)
            assertThat(body.roles).contains("ADMIN")
        }

        @Test
        @DisplayName("AC-002: Login rejects invalid credentials")
        fun `login_rejectsInvalidCredentials`() {
            // Given: Wrong password
            val request = LoginRequest(testUser.username, "wrongpassword")

            // When/Then: Should throw 401
            val exception = assertThrows<HttpClientResponseException> {
                client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/login", request),
                    LoginResponse::class.java
                )
            }

            assertThat(exception.status).isEqualTo(HttpStatus.UNAUTHORIZED)
        }

        @Test
        @DisplayName("AC-003: Login rejects empty username")
        fun `login_rejectsEmptyUsername`() {
            // Given: Empty username
            val request = LoginRequest("", TestDataFactory.DEFAULT_PASSWORD)

            // When/Then: Should throw 400
            val exception = assertThrows<HttpClientResponseException> {
                client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/login", request),
                    LoginResponse::class.java
                )
            }

            assertThat(exception.status).isEqualTo(HttpStatus.BAD_REQUEST)
        }

        @Test
        @DisplayName("AC-004: Login rejects empty password")
        fun `login_rejectsEmptyPassword`() {
            // Given: Empty password
            val request = LoginRequest(testUser.username, "")

            // When/Then: Should throw 400
            val exception = assertThrows<HttpClientResponseException> {
                client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/login", request),
                    LoginResponse::class.java
                )
            }

            assertThat(exception.status).isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    @Nested
    @DisplayName("Status Endpoint Tests")
    inner class StatusTests {

        @Test
        @DisplayName("AC-005: Status returns user info with valid token")
        fun `status_returnsUserInfo`() {
            // Given: Valid token from login
            val loginRequest = LoginRequest(testUser.username, TestDataFactory.DEFAULT_PASSWORD)
            val loginResponse = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login", loginRequest),
                LoginResponse::class.java
            )
            val token = loginResponse.body()!!.token

            // When: GET status with token
            val response = client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/auth/status").bearerAuth(token),
                StatusResponse::class.java
            )

            // Then: Returns user info
            assertThat(response.status).isEqualTo(HttpStatus.OK)
            val body = response.body()!!
            assertThat(body.username).isEqualTo(testUser.username)
            assertThat(body.email).isEqualTo(testUser.email)
            assertThat(body.roles).contains("ADMIN")
        }

        @Test
        @DisplayName("AC-006: Status rejects invalid token")
        fun `status_rejectsInvalidToken`() {
            // Given: Invalid token
            val invalidToken = "invalid.jwt.token"

            // When/Then: Should throw 401
            val exception = assertThrows<HttpClientResponseException> {
                client.toBlocking().exchange(
                    HttpRequest.GET<Any>("/api/auth/status").bearerAuth(invalidToken),
                    StatusResponse::class.java
                )
            }

            assertThat(exception.status).isEqualTo(HttpStatus.UNAUTHORIZED)
        }
    }
}
