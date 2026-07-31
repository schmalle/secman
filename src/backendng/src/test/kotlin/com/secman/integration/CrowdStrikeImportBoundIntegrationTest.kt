package com.secman.integration

import com.secman.domain.User
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The `@Size(max = 100)` bound on `POST /api/crowdstrike/servers/import`.
 *
 * The body is deserialized eagerly and the controller runs `@ExecuteOn(BLOCKING)` on virtual
 * threads, so without a cap the number of concurrent fully-parsed request bodies pinned in heap is
 * unbounded — a contributor to the 2026-07-30 import OOM.
 *
 * The assertion that actually matters is the STATUS CODE. A bean-validation failure on a body
 * parameter must surface as 400; if Micronaut's handler did not map `ConstraintViolationException`
 * here it would fall through as a 500, and the CLI's retry logic treats those very differently.
 * That mapping is an assumption worth pinning rather than trusting.
 *
 * transactional = false because these are real HTTP calls against data written in the test body —
 * see AccountFindingAgeIntegrationTest for the same reasoning.
 */
@DisplayName("CrowdStrike import: request payload bound")
@MicronautTest(environments = ["test"], transactional = false)
class CrowdStrikeImportBoundIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    private lateinit var adminUser: User
    private lateinit var username: String
    private lateinit var token: String

    /**
     * Unique username per test, and NO user deletion — matching AccountFindingAgeIntegrationTest.
     * Deleting a User here fails with an FK violation on the `user_roles` join table
     * (`User.roles` is an element collection), and the create-drop test schema makes cleanup
     * unnecessary anyway.
     */
    @BeforeEach
    fun setUp() {
        val unique = System.nanoTime()
        username = "bound-admin-$unique"
        adminUser = userRepository.save(
            TestDataFactory.createAdminUser(username = username, email = "$username@test.com")
        )
        token = TestAuthHelper.getAuthToken(client, username)
    }

    @AfterEach
    fun cleanup() {
        vulnerabilityRepository.deleteAll()
        assetRepository.deleteAll()
    }

    /** Minimal well-formed batch — one host, no vulnerabilities, so nothing heavy is imported. */
    private fun batch(index: Int) = CrowdStrikeVulnerabilityBatchDto(
        hostname = "bound-host-$index",
        groups = null,
        cloudAccountId = null,
        cloudInstanceId = null,
        adDomain = null,
        osVersion = null,
        ip = null,
        vulnerabilities = emptyList()
    )

    private fun post(batches: List<CrowdStrikeVulnerabilityBatchDto>) =
        HttpRequest.POST("/api/crowdstrike/servers/import", batches)
            .cookie(io.micronaut.http.cookie.Cookie.of(
                com.secman.service.AuthCookieService.AUTH_COOKIE_NAME, token))

    @Test
    @DisplayName("rejects more than 100 server batches with 400, not 500")
    fun oversizedPayloadIsRejectedAsBadRequest() {
        val tooMany = (1..101).map { batch(it) }

        val ex = try {
            client.toBlocking().exchange(post(tooMany), Any::class.java)
            null
        } catch (e: HttpClientResponseException) {
            e
        }

        assertThat(ex)
            .withFailMessage("101 batches should have been rejected by @Size(max = 100)")
            .isNotNull()
        assertThat(ex!!.status)
            .withFailMessage(
                "Expected 400 for a bean-validation failure, got %s. A 500 here means Micronaut is " +
                    "not mapping ConstraintViolationException on this body parameter.",
                ex.status
            )
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    @DisplayName("accepts a payload at the limit")
    fun payloadAtTheLimitIsAccepted() {
        val atLimit = (1..100).map { batch(it) }

        val status = try {
            client.toBlocking().exchange(post(atLimit), Any::class.java).status
        } catch (e: HttpClientResponseException) {
            e.status
        }

        assertThat(status)
            .withFailMessage("100 batches is exactly at the bound and must not be rejected")
            .isNotEqualTo(HttpStatus.BAD_REQUEST)
    }
}
