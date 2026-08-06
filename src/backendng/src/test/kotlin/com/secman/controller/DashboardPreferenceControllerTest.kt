package com.secman.controller

import com.secman.repository.DashboardPreferenceRepository
import com.secman.repository.UserRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Dashboard Preference Controller Tests")
class DashboardPreferenceControllerTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var dashboardPreferenceRepository: DashboardPreferenceRepository

    private lateinit var username: String

    @BeforeEach
    fun setupUser() {
        val suffix = System.nanoTime()
        username = "dashboard-pref-user-$suffix"
        userRepository.save(TestDataFactory.createRegularUser(
            username = username,
            email = "$username@test.com"
        ))
    }

    @Test
    fun `get returns defaults when no preference row exists`() {
        val token = TestAuthHelper.getAuthToken(client, username)

        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/dashboard-preferences").bearerAuth(token),
            DashboardPreferenceController.DashboardPreferenceResponse::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        val body = response.body()!!
        assertThat(body.showAwsCleanServerKpi).isTrue()
        assertThat(body.showEdrCoverageKpi).isTrue()
    }

    @Test
    fun `put persists preference and get returns persisted values`() {
        val token = TestAuthHelper.getAuthToken(client, username)

        val putResponse = client.toBlocking().exchange(
            HttpRequest.PUT(
                "/api/dashboard-preferences",
                DashboardPreferenceController.UpdatePreferenceRequest(
                    showAwsCleanServerKpi = false,
                    showEdrCoverageKpi = true
                )
            ).bearerAuth(token),
            DashboardPreferenceController.DashboardPreferenceResponse::class.java
        )

        assertThat(putResponse.status).isEqualTo(HttpStatus.OK)
        assertThat(putResponse.body()!!.showAwsCleanServerKpi).isFalse()
        assertThat(putResponse.body()!!.showEdrCoverageKpi).isTrue()

        val getResponse = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/dashboard-preferences").bearerAuth(token),
            DashboardPreferenceController.DashboardPreferenceResponse::class.java
        )

        assertThat(getResponse.body()!!.showAwsCleanServerKpi).isFalse()
        assertThat(getResponse.body()!!.showEdrCoverageKpi).isTrue()
    }

    @Test
    fun `put twice updates the existing row instead of creating a duplicate`() {
        val token = TestAuthHelper.getAuthToken(client, username)

        client.toBlocking().exchange(
            HttpRequest.PUT(
                "/api/dashboard-preferences",
                DashboardPreferenceController.UpdatePreferenceRequest(
                    showAwsCleanServerKpi = false,
                    showEdrCoverageKpi = false
                )
            ).bearerAuth(token),
            DashboardPreferenceController.DashboardPreferenceResponse::class.java
        )

        val secondResponse = client.toBlocking().exchange(
            HttpRequest.PUT(
                "/api/dashboard-preferences",
                DashboardPreferenceController.UpdatePreferenceRequest(
                    showAwsCleanServerKpi = true,
                    showEdrCoverageKpi = false
                )
            ).bearerAuth(token),
            DashboardPreferenceController.DashboardPreferenceResponse::class.java
        )

        assertThat(secondResponse.body()!!.showAwsCleanServerKpi).isTrue()
        assertThat(secondResponse.body()!!.showEdrCoverageKpi).isFalse()

        val user = userRepository.findByUsername(username).get()
        val rows = dashboardPreferenceRepository.findByUserId(user.id!!)
        assertThat(rows).isPresent
        assertThat(rows.get().showAwsCleanServerKpi).isTrue()
        assertThat(rows.get().showEdrCoverageKpi).isFalse()
    }
}
