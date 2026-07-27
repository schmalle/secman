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
    private lateinit var vulnUser: User
    private lateinit var secChampionUser: User
    private lateinit var releaseManagerUser: User

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
        vulnUser = userRepository.save(TestDataFactory.createVulnUser(
            username = "afa-vuln-${System.nanoTime()}",
            email = "afa-vuln-${System.nanoTime()}@test.com"
        ))
        secChampionUser = userRepository.save(TestDataFactory.createSecChampionUser(
            username = "afa-secchamp-${System.nanoTime()}",
            email = "afa-secchamp-${System.nanoTime()}@test.com"
        ))
        releaseManagerUser = userRepository.save(TestDataFactory.createUserWithRoles(
            username = "afa-relmgr-${System.nanoTime()}",
            email = "afa-relmgr-${System.nanoTime()}@test.com",
            User.Role.USER, User.Role.RELEASE_MANAGER
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
    fun `top endpoint rejects a VULN user with 403`() {
        val token = TestAuthHelper.getAuthToken(client, vulnUser.username)

        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/admin/account-finding-age/top").bearerAuth(token),
                List::class.java
            )
        }

        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `top endpoint rejects a SECCHAMPION user with 403`() {
        val token = TestAuthHelper.getAuthToken(client, secChampionUser.username)

        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/admin/account-finding-age/top").bearerAuth(token),
                List::class.java
            )
        }

        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `top endpoint rejects a RELEASE_MANAGER user with 403`() {
        val token = TestAuthHelper.getAuthToken(client, releaseManagerUser.username)

        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/admin/account-finding-age/top").bearerAuth(token),
                List::class.java
            )
        }

        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `name upsert rejects a non-admin user with 403`() {
        val token = TestAuthHelper.getAuthToken(client, regularUser.username)

        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.PUT("/api/admin/aws-accounts/777777777712/name", mapOf("name" to "X"))
                    .bearerAuth(token),
                Map::class.java
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

    /**
     * Generates a fresh 12-digit numeric AWS account id, unique per call, so tests never
     * collide with each other's fixed literal ids ("123456789012", "777777777777", ...) or
     * with rows left behind by earlier tests in this class (schema is create-drop per CLASS,
     * not per test — see class-level comment).
     */
    private fun uniqueAccountId(prefix: String): String {
        require(prefix.length in 1..3) { "prefix must leave room for a 9-12 digit suffix" }
        val suffix = (System.nanoTime() % 1_000_000_000_000L).toString().padStart(12 - prefix.length, '0')
        return (prefix + suffix).takeLast(12)
    }

    @Test
    fun `top endpoint orders three accounts oldest-first and resolves name fallback`() {
        val token = TestAuthHelper.getAuthToken(client, adminUser.username)

        // Three distinct accounts, clearly separated ages. Only the oldest gets a display
        // name; the other two must fall back to their bare account id in the response.
        val oldAccountId = uniqueAccountId("91")
        val midAccountId = uniqueAccountId("92")
        val newAccountId = uniqueAccountId("93")

        awsAccountRepository.save(AwsAccount(awsAccountId = oldAccountId, name = "Named Old Account"))

        fun seed(accountId: String, daysOld: Long, cve: String) {
            val asset = assetRepository.save(TestDataFactory.createAsset(
                name = "afa-multi-${accountId}-${System.nanoTime()}"
            ).apply { cloudAccountId = accountId; cloudInstanceId = "i-$accountId" })
            vulnerabilityRepository.save(TestDataFactory.createVulnerabilityWithTimestamp(
                asset, cve, "High", LocalDateTime.now().minusDays(daysOld)
            ))
        }

        seed(oldAccountId, 300, "CVE-MULTI-OLD")
        seed(midAccountId, 150, "CVE-MULTI-MID")
        seed(newAccountId, 20, "CVE-MULTI-NEW")

        // limit=MAX_LIMIT (50): guarantees all three seeded accounts are within the window
        // regardless of how many other accounts earlier tests in this class left behind —
        // the assertion below filters to just our three ids, so leaked rows from other tests
        // are irrelevant as long as they don't push us past the limit (they won't: at most a
        // handful of other ranked accounts exist across this whole test class).
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/admin/account-finding-age/top?limit=50").bearerAuth(token),
            io.micronaut.core.type.Argument.listOf(Map::class.java)
        )
        assertThat(response.status).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val ours = response.body()!!.map { it as Map<String, Any?> }
            .filter { it["awsAccountId"] in setOf(oldAccountId, midAccountId, newAccountId) }

        // Order, not just membership: if RANK_ACCOUNTS' ORDER BY were flipped to DESC (or
        // removed), this exact assertion would fail.
        assertThat(ours.map { it["awsAccountId"] }).containsExactly(oldAccountId, midAccountId, newAccountId)

        assertThat(ours[0]["accountName"]).isEqualTo("Named Old Account")
        // No AwsAccount row for these two -> name falls back to the bare account id.
        assertThat(ours[1]["accountName"]).isEqualTo(midAccountId)
        assertThat(ours[2]["accountName"]).isEqualTo(newAccountId)
    }

    @Test
    fun `name upsert with a blank name clears the stored name and the report falls back to the bare id`() {
        val token = TestAuthHelper.getAuthToken(client, adminUser.username)

        val accountId = uniqueAccountId("95")

        // Give this account an open finding so it's visible in the ranking report both
        // before and after the name is cleared.
        val asset = assetRepository.save(TestDataFactory.createAsset(
            name = "afa-blankname-${System.nanoTime()}"
        ).apply { cloudAccountId = accountId; cloudInstanceId = "i-blankname" })
        vulnerabilityRepository.save(TestDataFactory.createVulnerabilityWithTimestamp(
            asset, "CVE-BLANKNAME", "Medium", LocalDateTime.now().minusDays(60)
        ))

        // (a) PUT a real name, assert it stored.
        client.toBlocking().exchange(
            HttpRequest.PUT("/api/admin/aws-accounts/$accountId/name", mapOf("name" to "Real Name"))
                .bearerAuth(token),
            Map::class.java
        )
        assertThat(awsAccountRepository.findByAwsAccountId(accountId).get().name).isEqualTo("Real Name")

        // (b) PUT a blank (whitespace-only) name for the SAME account.
        client.toBlocking().exchange(
            HttpRequest.PUT("/api/admin/aws-accounts/$accountId/name", mapOf("name" to "   "))
                .bearerAuth(token),
            Map::class.java
        )

        val afterBlank = awsAccountRepository.findByAwsAccountId(accountId)
        assertThat(afterBlank).isPresent
        assertThat(afterBlank.get().name).isNull()

        // The fallback must be visible end-to-end where it actually matters: the report.
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/admin/account-finding-age/top?limit=50").bearerAuth(token),
            io.micronaut.core.type.Argument.listOf(Map::class.java)
        )
        assertThat(response.status).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val match = response.body()!!.map { it as Map<String, Any?> }.first { it["awsAccountId"] == accountId }
        assertThat(match["accountName"]).isEqualTo(accountId)
    }

    @Test
    fun `an excepted oldest finding is invisible to the ranking - the newer non-excepted finding wins`() {
        val token = TestAuthHelper.getAuthToken(client, adminUser.username)

        val accountId = uniqueAccountId("96")

        val asset = assetRepository.save(TestDataFactory.createAsset(
            name = "afa-excepted-${System.nanoTime()}"
        ).apply { cloudAccountId = accountId; cloudInstanceId = "i-excepted" })

        // Oldest finding is excepted -> must not be seen by the ranking at all.
        val exceptedVuln = TestDataFactory.createVulnerabilityWithTimestamp(
            asset, "CVE-EXCEPTED-OLDEST", "Critical", LocalDateTime.now().minusDays(500)
        )
        exceptedVuln.excepted = true
        vulnerabilityRepository.save(exceptedVuln)

        // Newer, non-excepted finding -> this is the one the report must surface.
        vulnerabilityRepository.save(TestDataFactory.createVulnerabilityWithTimestamp(
            asset, "CVE-VISIBLE-NEWER", "Low", LocalDateTime.now().minusDays(10)
        ))

        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/admin/account-finding-age/top?limit=50").bearerAuth(token),
            io.micronaut.core.type.Argument.listOf(Map::class.java)
        )
        assertThat(response.status).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val match = response.body()!!.map { it as Map<String, Any?> }.first { it["awsAccountId"] == accountId }

        assertThat(match["oldestFindingCve"]).isEqualTo("CVE-VISIBLE-NEWER")
        assertThat((match["oldestFindingDaysOpen"] as Number).toLong()).isLessThan(20L)
        assertThat((match["openFindingCount"] as Number).toLong()).isEqualTo(1L)
    }
}
