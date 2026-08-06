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

    /**
     * All cards visible unless a test names otherwise, so each test states only what it
     * actually exercises rather than restating every flag.
     */
    private fun request(
        showAwsCleanServerKpi: Boolean = true,
        showEdrCoverageKpi: Boolean = true,
        showAccountFindingAge: Boolean = true,
        showAssetInventory: Boolean = true,
        showUsers: Boolean = true,
        showActiveUsers: Boolean = true,
        showActiveReleases: Boolean = true,
        showRunningRiskAssessments: Boolean = true,
        showLastCrowdStrikeImport: Boolean = true
    ) = DashboardPreferenceController.UpdatePreferenceRequest(
        showAwsCleanServerKpi = showAwsCleanServerKpi,
        showEdrCoverageKpi = showEdrCoverageKpi,
        showAccountFindingAge = showAccountFindingAge,
        showAssetInventory = showAssetInventory,
        showUsers = showUsers,
        showActiveUsers = showActiveUsers,
        showActiveReleases = showActiveReleases,
        showRunningRiskAssessments = showRunningRiskAssessments,
        showLastCrowdStrikeImport = showLastCrowdStrikeImport
    )

    private fun get(token: String) = client.toBlocking().exchange(
        HttpRequest.GET<Any>("/api/dashboard-preferences").bearerAuth(token),
        DashboardPreferenceController.DashboardPreferenceResponse::class.java
    )

    private fun put(token: String, body: DashboardPreferenceController.UpdatePreferenceRequest) =
        client.toBlocking().exchange(
            HttpRequest.PUT("/api/dashboard-preferences", body).bearerAuth(token),
            DashboardPreferenceController.DashboardPreferenceResponse::class.java
        )

    @Test
    fun `get returns every card visible when no preference row exists`() {
        val token = TestAuthHelper.getAuthToken(client, username)

        val response = get(token)

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        val body = response.body()!!
        assertThat(body.showAwsCleanServerKpi).isTrue()
        assertThat(body.showEdrCoverageKpi).isTrue()
        assertThat(body.showAccountFindingAge).isTrue()
        assertThat(body.showAssetInventory).isTrue()
        assertThat(body.showUsers).isTrue()
        assertThat(body.showActiveUsers).isTrue()
        assertThat(body.showActiveReleases).isTrue()
        assertThat(body.showRunningRiskAssessments).isTrue()
        assertThat(body.showLastCrowdStrikeImport).isTrue()
    }

    @Test
    fun `put persists preference and get returns persisted values`() {
        val token = TestAuthHelper.getAuthToken(client, username)

        val putResponse = put(token, request(showAwsCleanServerKpi = false, showAccountFindingAge = false))

        assertThat(putResponse.status).isEqualTo(HttpStatus.OK)
        assertThat(putResponse.body()!!.showAwsCleanServerKpi).isFalse()
        assertThat(putResponse.body()!!.showEdrCoverageKpi).isTrue()
        assertThat(putResponse.body()!!.showAccountFindingAge).isFalse()

        val getResponse = get(token)

        assertThat(getResponse.body()!!.showAwsCleanServerKpi).isFalse()
        assertThat(getResponse.body()!!.showEdrCoverageKpi).isTrue()
        assertThat(getResponse.body()!!.showAccountFindingAge).isFalse()
    }

    @Test
    fun `put persists the non-security cards independently`() {
        val token = TestAuthHelper.getAuthToken(client, username)

        put(token, request(
            showAssetInventory = false,
            showUsers = false,
            showActiveUsers = true,
            showActiveReleases = false,
            showRunningRiskAssessments = true,
            showLastCrowdStrikeImport = false
        ))

        val body = get(token).body()!!
        assertThat(body.showAssetInventory).isFalse()
        assertThat(body.showUsers).isFalse()
        assertThat(body.showActiveUsers).isTrue()
        assertThat(body.showActiveReleases).isFalse()
        assertThat(body.showRunningRiskAssessments).isTrue()
        assertThat(body.showLastCrowdStrikeImport).isFalse()
        // The security KPI flags were left at their defaults and must be untouched.
        assertThat(body.showAwsCleanServerKpi).isTrue()
        assertThat(body.showEdrCoverageKpi).isTrue()
    }

    @Test
    fun `put twice updates the existing row instead of creating a duplicate`() {
        val token = TestAuthHelper.getAuthToken(client, username)

        put(token, request(
            showAwsCleanServerKpi = false,
            showEdrCoverageKpi = false,
            showAccountFindingAge = false,
            showAssetInventory = false
        ))

        val secondResponse = put(token, request(
            showEdrCoverageKpi = false,
            showAssetInventory = false
        ))

        assertThat(secondResponse.body()!!.showAwsCleanServerKpi).isTrue()
        assertThat(secondResponse.body()!!.showEdrCoverageKpi).isFalse()
        assertThat(secondResponse.body()!!.showAccountFindingAge).isTrue()
        assertThat(secondResponse.body()!!.showAssetInventory).isFalse()

        val user = userRepository.findByUsername(username).get()
        val rows = dashboardPreferenceRepository.findByUserId(user.id!!)
        assertThat(rows).isPresent
        assertThat(rows.get().showAwsCleanServerKpi).isTrue()
        assertThat(rows.get().showEdrCoverageKpi).isFalse()
        assertThat(rows.get().showAccountFindingAge).isTrue()
        assertThat(rows.get().showAssetInventory).isFalse()
    }
}
