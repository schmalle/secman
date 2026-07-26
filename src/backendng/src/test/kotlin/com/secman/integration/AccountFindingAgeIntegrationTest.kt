package com.secman.integration

import com.secman.domain.AwsAccount
import com.secman.domain.User
import com.secman.repository.AwsAccountRepository
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

// transactional = false: this class makes real HTTP calls (via the embedded server, on a
// separate connection) against data written directly in test bodies. Micronaut's default
// per-test rollback transaction only covers the test thread's own connection, so writes made
// inside a @Test body are invisible to the HTTP-handling thread unless this is disabled. Same
// pattern as GithubRepositoryControllerIntegrationTest.
@MicronautTest(environments = ["test"], transactional = false)
class AccountFindingAgeIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var awsAccountRepository: AwsAccountRepository

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
    private lateinit var regularUser: User

    @BeforeEach
    fun setupTestUsers() {
        adminUser = userRepository.save(TestDataFactory.createAdminUser(
            username = "afa-admin-${System.nanoTime()}",
            email = "afa-admin-${System.nanoTime()}@test.com"
        ))
        regularUser = userRepository.save(TestDataFactory.createRegularUser(
            username = "afa-user-${System.nanoTime()}",
            email = "afa-user-${System.nanoTime()}@test.com"
        ))
    }

    @Test
    fun `aws account row round-trips by account id`() {
        awsAccountRepository.save(
            AwsAccount(awsAccountId = "123456789012", name = "Platform Prod", updatedBy = "admin")
        )

        val found = awsAccountRepository.findByAwsAccountId("123456789012")

        assertThat(found).isPresent
        assertThat(found.get().name).isEqualTo("Platform Prod")
        assertThat(found.get().updatedAt).isNotNull()
    }

    @Test
    fun `bulk lookup returns only known accounts`() {
        awsAccountRepository.save(AwsAccount(awsAccountId = "222222222222", name = "Sandbox"))

        val rows = awsAccountRepository.findByAwsAccountIdIn(listOf("222222222222", "999999999999"))

        assertThat(rows).hasSize(1)
        assertThat(rows.first().awsAccountId).isEqualTo("222222222222")
    }

    @Test
    fun `top endpoint rejects a non-admin user with 403`() {
        val token = TestAuthHelper.getAuthToken(client, regularUser.username)

        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/admin/account-finding-age/top").bearerAuth(token),
                List::class.java
            )
        }

        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `top endpoint returns 200 for an admin`() {
        val token = TestAuthHelper.getAuthToken(client, adminUser.username)

        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/admin/account-finding-age/top").bearerAuth(token),
            String::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `top endpoint rejects an out-of-range limit with 400`() {
        val token = TestAuthHelper.getAuthToken(client, adminUser.username)

        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/admin/account-finding-age/top?limit=999").bearerAuth(token),
                List::class.java
            )
        }

        assertThat(ex.status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `name upsert stores the name and is readable back`() {
        val token = TestAuthHelper.getAuthToken(client, adminUser.username)

        client.toBlocking().exchange(
            HttpRequest.PUT("/api/admin/aws-accounts/777777777777/name", mapOf("name" to "Data Platform"))
                .bearerAuth(token),
            Map::class.java
        )

        assertThat(awsAccountRepository.findByAwsAccountId("777777777777").get().name)
            .isEqualTo("Data Platform")
    }

    @Test
    fun `name upsert rejects a malformed account id with 400`() {
        val token = TestAuthHelper.getAuthToken(client, adminUser.username)

        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.PUT("/api/admin/aws-accounts/not-an-id/name", mapOf("name" to "X")).bearerAuth(token),
                Map::class.java
            )
        }

        assertThat(ex.status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `top endpoint ranks accounts oldest-first with real seeded data`() {
        val token = TestAuthHelper.getAuthToken(client, adminUser.username)

        val accountId = "88855500${(System.nanoTime() % 10000).toString().padStart(4, '0')}"
        awsAccountRepository.save(AwsAccount(awsAccountId = accountId, name = "Seeded Account"))

        val olderAsset = assetRepository.save(TestDataFactory.createAsset(
            name = "afa-old-asset-${System.nanoTime()}"
        ).apply { cloudAccountId = accountId; cloudInstanceId = "i-old" })
        val newerAsset = assetRepository.save(TestDataFactory.createAsset(
            name = "afa-new-asset-${System.nanoTime()}"
        ).apply { cloudAccountId = accountId; cloudInstanceId = "i-new" })

        val oldestTimestamp = LocalDateTime.now().minusDays(200)
        val newerTimestamp = LocalDateTime.now().minusDays(5)

        vulnerabilityRepository.save(TestDataFactory.createVulnerabilityWithTimestamp(
            olderAsset, "CVE-2020-OLDEST", "Critical", oldestTimestamp
        ))
        vulnerabilityRepository.save(TestDataFactory.createVulnerabilityWithTimestamp(
            newerAsset, "CVE-2024-NEWER", "Low", newerTimestamp
        ))

        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/admin/account-finding-age/top").bearerAuth(token),
            io.micronaut.core.type.Argument.listOf(Map::class.java)
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        val body = response.body()!!
        @Suppress("UNCHECKED_CAST")
        val match = body.map { it as Map<String, Any?> }.first { it["awsAccountId"] == accountId }

        assertThat(match["accountName"]).isEqualTo("Seeded Account")
        assertThat(match["oldestFindingCve"]).isEqualTo("CVE-2020-OLDEST")
        assertThat(match["oldestFindingSeverity"]).isEqualTo("Critical")
        assertThat(match["oldestFindingAssetInstanceId"]).isEqualTo("i-old")
        assertThat((match["openFindingCount"] as Number).toLong()).isEqualTo(2L)
        assertThat((match["affectedAssetCount"] as Number).toLong()).isEqualTo(2L)
        assertThat((match["oldestFindingDaysOpen"] as Number).toLong()).isGreaterThanOrEqualTo(199L)
    }
}
