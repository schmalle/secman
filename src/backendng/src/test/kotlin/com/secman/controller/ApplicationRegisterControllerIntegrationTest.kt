package com.secman.controller

import com.secman.domain.User
import com.secman.dto.ApplicationRegisterDetail
import com.secman.dto.ApplicationRegisterRequest
import com.secman.dto.ApplicationRegisterSummary
import com.secman.repository.UserRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ApplicationRegisterControllerIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    private lateinit var regularUser: User
    private lateinit var adminUser: User

    @BeforeEach
    fun setUp() {
        val suffix = System.nanoTime()
        regularUser = userRepository.save(TestDataFactory.createRegularUser("app-user-$suffix", "app-user-$suffix@test.com"))
        adminUser = userRepository.save(TestDataFactory.createAdminUser("app-admin-$suffix", "app-admin-$suffix@test.com"))
    }

    @Test
    fun `authenticated user can list and get applications`() {
        val adminToken = TestAuthHelper.getAuthToken(client, adminUser.username)
        val created = client.toBlocking().exchange(
            TestAuthHelper.authenticatedRequest(
                HttpMethod.POST,
                "/api/applications",
                validRequest(carId = "CAR-${System.nanoTime()}"),
                adminToken
            ),
            ApplicationRegisterDetail::class.java
        ).body()!!
        val token = TestAuthHelper.getAuthToken(client, regularUser.username)

        val listResponse = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/applications?search=Application").bearerAuth(token),
            Argument.listOf(ApplicationRegisterSummary::class.java)
        )
        val detailResponse = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/applications/${created.id}").bearerAuth(token),
            ApplicationRegisterDetail::class.java
        )

        assertThat(listResponse.status).isEqualTo(HttpStatus.OK)
        assertThat(listResponse.body()!!.map { it.id }).contains(created.id)
        assertThat(detailResponse.status).isEqualTo(HttpStatus.OK)
        assertThat(detailResponse.body()!!.carId).isEqualTo(created.carId)
    }

    @Test
    fun `regular user cannot create applications`() {
        val token = TestAuthHelper.getAuthToken(client, regularUser.username)

        val exception = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                TestAuthHelper.authenticatedRequest(
                    HttpMethod.POST,
                    "/api/applications",
                    validRequest(),
                    token
                ),
                ApplicationRegisterDetail::class.java
            )
        }

        assertThat(exception.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `admin can create applications`() {
        val token = TestAuthHelper.getAuthToken(client, adminUser.username)

        val response = client.toBlocking().exchange(
            TestAuthHelper.authenticatedRequest(
                HttpMethod.POST,
                "/api/applications",
                validRequest(carId = "CAR-${System.nanoTime()}"),
                token
            ),
            ApplicationRegisterDetail::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.CREATED)
        assertThat(response.body()!!.createdBy).isEqualTo(adminUser.username)
    }

    @Test
    fun `check-github-url rejects non-github hosts to prevent SSRF`() {
        val token = TestAuthHelper.getAuthToken(client, regularUser.username)

        // Attempting to probe an internal/arbitrary host must be refused server-side,
        // not merely relied upon in the frontend caller.
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/applications/check-github-url?url=http://169.254.169.254/latest/meta-data/")
                .bearerAuth(token),
            Map::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body()?.get("reachable")).isEqualTo(false)
    }

    @Test
    fun `check-github-url rejects plain http even for github host`() {
        val token = TestAuthHelper.getAuthToken(client, regularUser.username)

        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/applications/check-github-url?url=http://github.com/foo/bar")
                .bearerAuth(token),
            Map::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body()?.get("reachable")).isEqualTo(false)
        assertThat(response.body()?.get("reason")).isEqualTo("URL must use https")
    }

    private fun validRequest(carId: String = "CAR-${System.nanoTime()}"): ApplicationRegisterRequest {
        return ApplicationRegisterRequest(
            carId = carId,
            name = "Application",
            businessOwner = "Owner",
            applicationManager = "Manager"
        )
    }
}
