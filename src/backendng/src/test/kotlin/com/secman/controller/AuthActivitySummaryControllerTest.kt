package com.secman.controller

import com.secman.domain.User
import com.secman.repository.UserRepository
import com.secman.service.AuthCookieService
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIf

@DisplayName("Auth Activity Summary Controller Tests")
@EnabledIf("com.secman.testutil.DockerAvailable#isDockerAvailable")
class AuthActivitySummaryControllerTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    private lateinit var adminUser: User
    private lateinit var regularUser: User

    @Serdeable
    data class LoginRequest(val username: String, val password: String)

    @Serdeable
    data class ActivitySummaryResponse(
        val activeUsers: Int,
        val windowSeconds: Long,
        val generatedAt: String
    )

    @BeforeEach
    fun setupUsers() {
        val suffix = System.nanoTime()
        adminUser = userRepository.save(TestDataFactory.createAdminUser(
            username = "activity-admin-$suffix",
            email = "activity-admin-$suffix@test.com"
        ))
        regularUser = userRepository.save(TestDataFactory.createRegularUser(
            username = "activity-user-$suffix",
            email = "activity-user-$suffix@test.com"
        ))
    }

    @Test
    fun `admin can fetch active user summary`() {
        val adminCookie = loginCookie(adminUser.username)

        client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/auth/heartbeat").cookie(adminCookie),
            Argument.STRING
        )

        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/auth/activity-summary").cookie(adminCookie),
            ActivitySummaryResponse::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body()!!.activeUsers).isGreaterThanOrEqualTo(1)
        assertThat(response.body()!!.windowSeconds).isEqualTo(900)
        assertThat(response.body()!!.generatedAt).isNotBlank()
    }

    @Test
    fun `unauthenticated request is rejected`() {
        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/auth/activity-summary"),
                ActivitySummaryResponse::class.java
            )
        }

        assertThat(exception.status).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `non-admin request is rejected`() {
        val userCookie = loginCookie(regularUser.username)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/auth/activity-summary").cookie(userCookie),
                ActivitySummaryResponse::class.java
            )
        }

        assertThat(exception.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    private fun loginCookie(username: String): io.micronaut.http.cookie.Cookie {
        val response = client.toBlocking().exchange(
            HttpRequest.POST("/api/auth/login", LoginRequest(username, TestDataFactory.DEFAULT_PASSWORD)),
            Argument.STRING
        )

        return response.cookies.get(AuthCookieService.AUTH_COOKIE_NAME)
            ?: throw IllegalStateException("Login response did not include ${AuthCookieService.AUTH_COOKIE_NAME} cookie")
    }
}
