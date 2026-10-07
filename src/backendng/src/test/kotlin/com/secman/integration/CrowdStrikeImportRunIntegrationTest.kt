package com.secman.integration

import com.secman.repository.UserRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@io.micronaut.test.extensions.junit5.annotation.MicronautTest(environments = ["test"], transactional = false)
class CrowdStrikeImportRunIntegrationTest : BaseIntegrationTest() {
    @Inject @field:Client("/") lateinit var client: HttpClient
    @Inject lateinit var users: UserRepository
    private val endpoint = "/api/crowdstrike/servers/import/runs"

    @Test
    fun `http lifecycle enforces roles owner and competing run guard`() {
        val stamp = System.nanoTime()
        val admin = users.save(TestDataFactory.createAdminUser(username = "run-admin-$stamp", email = "admin-$stamp@test.com"))
        val other = users.save(TestDataFactory.createVulnUser(username = "run-other-$stamp", email = "other-$stamp@test.com"))
        val viewer = users.save(TestDataFactory.createRegularUser(username = "run-viewer-$stamp", email = "viewer-$stamp@test.com"))
        val adminToken = TestAuthHelper.getAuthToken(client, admin.username)
        val otherToken = TestAuthHelper.getAuthToken(client, other.username)
        val viewerToken = TestAuthHelper.getAuthToken(client, viewer.username)
        fun post(path: String, token: String) = client.toBlocking().exchange(
            HttpRequest.POST(path, emptyMap<String, String>()).bearerAuth(token), Map::class.java)
        fun denied(path: String, token: String, status: HttpStatus) {
            try {
                post(path, token)
                error("Expected denial")
            } catch (e: HttpClientResponseException) { assertThat(e.status).isEqualTo(status) }
        }
        denied(endpoint, viewerToken, HttpStatus.FORBIDDEN)
        val runId = post(endpoint, adminToken).body()!!["runId"].toString()
        try {
            denied(endpoint, otherToken, HttpStatus.CONFLICT)
            denied("$endpoint/$runId/heartbeat", otherToken, HttpStatus.CONFLICT)
            denied("$endpoint/$runId/finish?successful=true", otherToken, HttpStatus.CONFLICT)
            assertThat(post("$endpoint/$runId/heartbeat", adminToken).status).isEqualTo(HttpStatus.OK)
        } finally {
            assertThat(post("$endpoint/$runId/finish?successful=false", adminToken).body()!!["status"]).isEqualTo("FAILED")
        }
        denied("$endpoint/$runId/heartbeat", adminToken, HttpStatus.CONFLICT)
    }
}
