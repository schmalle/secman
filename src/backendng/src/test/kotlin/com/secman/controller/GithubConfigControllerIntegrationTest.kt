package com.secman.controller

import com.secman.domain.GithubAppConfig
import com.secman.domain.User
import com.secman.repository.GithubAppConfigRepository
import com.secman.repository.UserRepository
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * GitHub App config endpoint coverage: ADMIN-only access, private key
 * masking in responses, mask-aware updates (sentinel must not overwrite
 * the stored key), single-active invariant.
 */
@MicronautTest(environments = ["test"], transactional = false)
class GithubConfigControllerIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var configRepository: GithubAppConfigRepository

    private lateinit var adminUser: User
    private lateinit var vulnUser: User

    private val testPem = "-----BEGIN RSA PRIVATE KEY-----\nintegration-test-key\n-----END RSA PRIVATE KEY-----"

    @BeforeEach
    fun setUp() {
        val suffix = System.nanoTime()
        adminUser = userRepository.save(TestDataFactory.createAdminUser("ghc-admin-$suffix", "ghc-admin-$suffix@test.com"))
        vulnUser = userRepository.save(TestDataFactory.createVulnUser("ghc-vuln-$suffix", "ghc-vuln-$suffix@test.com"))
    }

    @Test
    fun `create returns masked key and GET stays masked`() {
        val adminToken = TestAuthHelper.getAuthToken(client, adminUser.username)

        val created = client.toBlocking().exchange(
            HttpRequest.POST(
                "/api/github-config",
                mapOf("appId" to "12345", "privateKeyPem" to testPem, "organization" to "test-org")
            ).bearerAuth(adminToken),
            GithubAppConfig::class.java
        )
        assertThat(created.status).isEqualTo(HttpStatus.CREATED)
        assertThat(created.body()!!.privateKeyPem).isEqualTo(GithubAppConfig.PRIVATE_KEY_MASK)
        val id = created.body()!!.id!!

        val fetched = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/github-config/$id").bearerAuth(adminToken),
            GithubAppConfig::class.java
        )
        assertThat(fetched.body()!!.privateKeyPem).isEqualTo(GithubAppConfig.PRIVATE_KEY_MASK)
        assertThat(fetched.body()!!.appId).isEqualTo("12345")

        // The stored key must be the real one, not the mask
        assertThat(configRepository.findById(id).get().privateKeyPem).isEqualTo(testPem)
    }

    @Test
    fun `update with masked sentinel keeps the stored key`() {
        val adminToken = TestAuthHelper.getAuthToken(client, adminUser.username)
        val created = client.toBlocking().exchange(
            HttpRequest.POST(
                "/api/github-config",
                mapOf("appId" to "22222", "privateKeyPem" to testPem)
            ).bearerAuth(adminToken),
            GithubAppConfig::class.java
        )
        val id = created.body()!!.id!!

        val updated = client.toBlocking().exchange(
            HttpRequest.PUT(
                "/api/github-config/$id",
                mapOf("appId" to "33333", "privateKeyPem" to GithubAppConfig.PRIVATE_KEY_MASK)
            ).bearerAuth(adminToken),
            GithubAppConfig::class.java
        )
        assertThat(updated.status).isEqualTo(HttpStatus.OK)
        assertThat(updated.body()!!.appId).isEqualTo("33333")

        val stored = configRepository.findById(id).get()
        assertThat(stored.privateKeyPem).isEqualTo(testPem)
    }

    @Test
    fun `creating a second config deactivates the first`() {
        val adminToken = TestAuthHelper.getAuthToken(client, adminUser.username)
        val first = client.toBlocking().exchange(
            HttpRequest.POST(
                "/api/github-config",
                mapOf("appId" to "44444", "privateKeyPem" to testPem)
            ).bearerAuth(adminToken),
            GithubAppConfig::class.java
        ).body()!!

        val second = client.toBlocking().exchange(
            HttpRequest.POST(
                "/api/github-config",
                mapOf("appId" to "55555", "privateKeyPem" to testPem)
            ).bearerAuth(adminToken),
            GithubAppConfig::class.java
        ).body()!!

        assertThat(second.isActive).isTrue()
        assertThat(configRepository.findById(first.id!!).get().isActive).isFalse()
    }

    @Test
    fun `validation rejects non-numeric appId`() {
        val adminToken = TestAuthHelper.getAuthToken(client, adminUser.username)
        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST(
                    "/api/github-config",
                    mapOf("appId" to "not-numeric", "privateKeyPem" to testPem)
                ).bearerAuth(adminToken),
                String::class.java
            )
        }
        assertThat(ex.status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `non-admin is denied`() {
        val vulnToken = TestAuthHelper.getAuthToken(client, vulnUser.username)
        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/github-config").bearerAuth(vulnToken),
                String::class.java
            )
        }
        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }
}
