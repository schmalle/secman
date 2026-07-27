package com.secman.integration

import com.secman.domain.CrowdStrikeReconcileJob
import com.secman.domain.ReconcileJobStatus
import com.secman.domain.User
import com.secman.dto.ReconcileStaleVulnerabilitiesRequest
import com.secman.repository.CrowdStrikeReconcileJobRepository
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.time.LocalDateTime

/**
 * Integration tests for the async reconcile-stale job endpoints
 * (POST 202 + jobId, GET status polling, single-job 409 guard).
 *
 * The sweep runs as a background job because the old synchronous endpoint
 * exceeded nginx's 60s proxy timeout on large tables (504 to the CLI while
 * the backend completed fine).
 *
 * transactional=false: the 409-guard test seeds a RUNNING job row from the test
 * method body and needs the HTTP server thread to see it. With the default
 * test-transaction wrapping, test-body writes stay uncommitted (only @BeforeEach
 * writes commit, since the wrapping starts at beforeTestExecution) and the guard
 * would never fire. Cleanup is handled explicitly via deleteAll() in setUp.
 */
@io.micronaut.test.extensions.junit5.annotation.MicronautTest(environments = ["test"], transactional = false)
@DisplayName("CrowdStrike Reconcile Job Integration Tests")
class CrowdStrikeReconcileJobIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var jobRepository: CrowdStrikeReconcileJobRepository

    private lateinit var adminUser: User

    @BeforeEach
    fun setUp() {
        // A leftover PENDING/RUNNING row from a previous test would trip the 409 guard.
        jobRepository.deleteAll()
        adminUser = userRepository.save(
            TestDataFactory.createAdminUser(
                username = "reconcile-admin-${System.nanoTime()}",
                email = "reconcile-admin-${System.nanoTime()}@test.com"
            )
        )
    }

    private fun reconcileRequest() = ReconcileStaleVulnerabilitiesRequest(
        importStartedAt = LocalDateTime.now().minusMinutes(5),
        severities = listOf("CRITICAL", "HIGH"),
        // Empty queried-host population -> the sweep resolves 0 assets and finishes
        // immediately with rowsDeleted=0 (fail-safe path) - keeps the job trivially fast.
        queriedHosts = emptyList()
    )

    @Test
    @DisplayName("POST returns 202 with jobId and the job completes with a result")
    fun `reconcile job lifecycle completes`() {
        val token = TestAuthHelper.getAuthToken(client, adminUser.username)

        val response = client.toBlocking().exchange(
            HttpRequest.POST("/api/crowdstrike/servers/reconcile-stale", reconcileRequest())
                .header("Authorization", "Bearer $token"),
            Map::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.ACCEPTED)
        val jobId = response.body()?.get("jobId")?.toString()
        assertThat(jobId).isNotBlank()

        // Poll until terminal (the empty-host sweep finishes in well under 30s)
        val deadline = System.currentTimeMillis() + 30_000
        var statusBody: Map<*, *>? = null
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            statusBody = client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/crowdstrike/servers/reconcile-stale/$jobId/status")
                    .header("Authorization", "Bearer $token"),
                Map::class.java
            ).body()
            val status = statusBody?.get("status")?.toString()
            if (status == "COMPLETED" || status == "FAILED") break
        }

        assertThat(statusBody?.get("status")).isEqualTo("COMPLETED")
        val result = statusBody?.get("result") as? Map<*, *>
            ?: fail("COMPLETED job carried no result: $statusBody")
        assertThat((result["rowsDeleted"] as Number).toInt()).isEqualTo(0)
        assertThat(result["aborted"]).isEqualTo(false)
    }

    @Test
    @DisplayName("POST while another job is running returns 409 with the running jobId")
    fun `second start while running returns conflict`() {
        val token = TestAuthHelper.getAuthToken(client, adminUser.username)
        val running = jobRepository.save(
            CrowdStrikeReconcileJob(id = "it-running-job", username = adminUser.username)
                .apply { status = ReconcileJobStatus.RUNNING }
        )

        try {
            client.toBlocking().exchange(
                HttpRequest.POST("/api/crowdstrike/servers/reconcile-stale", reconcileRequest())
                    .header("Authorization", "Bearer $token"),
                Map::class.java
            )
            fail("Expected 409 Conflict")
        } catch (e: HttpClientResponseException) {
            assertThat(e.status).isEqualTo(HttpStatus.CONFLICT)
            @Suppress("UNCHECKED_CAST")
            val body = e.response.getBody(Map::class.java).orElse(null) as? Map<String, Any?>
            assertThat(body?.get("jobId")).isEqualTo(running.id)
        }
    }

    @Test
    @DisplayName("status of an unknown job returns 404")
    fun `unknown job returns not found`() {
        val token = TestAuthHelper.getAuthToken(client, adminUser.username)

        try {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/crowdstrike/servers/reconcile-stale/no-such-job/status")
                    .header("Authorization", "Bearer $token"),
                Map::class.java
            )
            fail("Expected 404 Not Found")
        } catch (e: HttpClientResponseException) {
            assertThat(e.status).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }
}
