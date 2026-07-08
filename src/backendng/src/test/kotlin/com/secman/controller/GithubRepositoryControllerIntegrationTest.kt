package com.secman.controller

import com.secman.domain.GithubRepoAlertException
import com.secman.domain.GithubRepoFindingSnapshot
import com.secman.domain.GithubRepository
import com.secman.domain.User
import com.secman.repository.GithubRepoAlertExceptionRepository
import com.secman.repository.GithubRepoFindingSnapshotRepository
import com.secman.repository.GithubRepositoryRepository
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
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * RBAC + lifecycle coverage for the GitHub repository endpoints
 * (Feature: GitHub repo vulnerability management):
 * - GET  /api/github/repositories                (ADMIN/VULN/SECCHAMPION)
 * - PUT  /api/github/repositories/{id}/owner-email (ADMIN/VULN)
 * - POST/DELETE /api/github/repo-alert-exceptions (ADMIN/VULN)
 * - POST /api/cli/github-repo-alerts/send        (ADMIN)
 */
@MicronautTest(environments = ["test"], transactional = false)
class GithubRepositoryControllerIntegrationTest : BaseIntegrationTest() {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var githubRepositoryRepository: GithubRepositoryRepository

    @Inject
    lateinit var snapshotRepository: GithubRepoFindingSnapshotRepository

    @Inject
    lateinit var exceptionRepository: GithubRepoAlertExceptionRepository

    private lateinit var adminUser: User
    private lateinit var vulnUser: User
    private lateinit var champUser: User
    private lateinit var regularUser: User

    @BeforeEach
    fun setUp() {
        val suffix = System.nanoTime()
        adminUser = userRepository.save(TestDataFactory.createAdminUser("gh-admin-$suffix", "gh-admin-$suffix@test.com"))
        vulnUser = userRepository.save(TestDataFactory.createVulnUser("gh-vuln-$suffix", "gh-vuln-$suffix@test.com"))
        champUser = userRepository.save(TestDataFactory.createSecChampionUser("gh-champ-$suffix", "gh-champ-$suffix@test.com"))
        regularUser = userRepository.save(TestDataFactory.createRegularUser("gh-user-$suffix", "gh-user-$suffix@test.com"))
    }

    private fun seedRepo(critical: Int = 0, high: Int = 0, ownerEmail: String? = null): GithubRepository {
        val suffix = System.nanoTime()
        return githubRepositoryRepository.save(
            GithubRepository(
                githubRepoId = suffix,
                name = "repo-$suffix",
                owner = "test-org",
                fullName = "test-org/repo-$suffix",
                htmlUrl = "https://github.com/test-org/repo-$suffix",
                ownerEmail = ownerEmail,
                criticalCount = critical,
                highCount = high,
                lastImportAt = Instant.now()
            )
        )
    }

    @Test
    fun `SECCHAMPION can list repositories, regular user is denied`() {
        val repo = seedRepo(critical = 3, high = 1)

        val champToken = TestAuthHelper.getAuthToken(client, champUser.username)
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/github/repositories").bearerAuth(champToken),
            Array<GithubRepositoryController.GithubRepositoryDto>::class.java
        )
        assertThat(response.status).isEqualTo(HttpStatus.OK)
        val row = response.body()!!.first { it.id == repo.id }
        assertThat(row.criticalCount).isEqualTo(3)
        assertThat(row.highCount).isEqualTo(1)
        assertThat(row.activeException).isNull()

        val userToken = TestAuthHelper.getAuthToken(client, regularUser.username)
        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/github/repositories").bearerAuth(userToken),
                String::class.java
            )
        }
        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `VULN can update owner email, SECCHAMPION cannot`() {
        val repo = seedRepo()
        val vulnToken = TestAuthHelper.getAuthToken(client, vulnUser.username)

        val ok = client.toBlocking().exchange(
            HttpRequest.PUT(
                "/api/github/repositories/${repo.id}/owner-email",
                mapOf("ownerEmail" to "Owner@Example.COM")
            ).bearerAuth(vulnToken),
            Map::class.java
        )
        assertThat(ok.status).isEqualTo(HttpStatus.OK)
        assertThat(githubRepositoryRepository.findById(repo.id!!).get().ownerEmail)
            .isEqualTo("owner@example.com") // normalized to lowercase

        // Invalid email rejected
        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.PUT(
                    "/api/github/repositories/${repo.id}/owner-email",
                    mapOf("ownerEmail" to "not-an-email")
                ).bearerAuth(vulnToken),
                String::class.java
            )
        }
        assertThat(ex.status).isEqualTo(HttpStatus.BAD_REQUEST)

        val champToken = TestAuthHelper.getAuthToken(client, champUser.username)
        val champEx = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.PUT(
                    "/api/github/repositories/${repo.id}/owner-email",
                    mapOf("ownerEmail" to "x@y.zz")
                ).bearerAuth(champToken),
                String::class.java
            )
        }
        assertThat(champEx.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `exception lifecycle - create, surfaces in repo list, delete`() {
        val repo = seedRepo(critical = 2)
        val adminToken = TestAuthHelper.getAuthToken(client, adminUser.username)

        val created = client.toBlocking().exchange(
            HttpRequest.POST(
                "/api/github/repo-alert-exceptions",
                mapOf("githubRepositoryId" to repo.id, "reason" to "accepted risk for integration test")
            ).bearerAuth(adminToken),
            GithubRepoAlertException::class.java
        )
        assertThat(created.status).isEqualTo(HttpStatus.CREATED)
        val exceptionId = created.body()!!.id!!
        assertThat(created.body()!!.createdBy).isEqualTo(adminUser.username)

        val listResponse = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/github/repositories").bearerAuth(adminToken),
            Array<GithubRepositoryController.GithubRepositoryDto>::class.java
        )
        val row = listResponse.body()!!.first { it.id == repo.id }
        assertThat(row.activeException).isNotNull()
        assertThat(row.activeException!!.reason).isEqualTo("accepted risk for integration test")

        val deleted = client.toBlocking().exchange(
            HttpRequest.DELETE<Any>("/api/github/repo-alert-exceptions/$exceptionId").bearerAuth(adminToken),
            String::class.java
        )
        assertThat(deleted.status).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(exceptionRepository.findById(exceptionId)).isEmpty()
    }

    @Test
    fun `alert dry-run classifies repos into alerted, excepted, unmapped and skipped`() {
        val now = Instant.now()

        // Non-decreasing with owner email -> alerted + recipient
        val alertedRepo = seedRepo(critical = 2, high = 1, ownerEmail = "gh-owner@test.com")
        snapshotRepository.save(
            GithubRepoFindingSnapshot(
                githubRepositoryId = alertedRepo.id!!,
                snapshotAt = now.minus(31, ChronoUnit.DAYS),
                criticalCount = 2, highCount = 1
            )
        )

        // Non-decreasing without owner email -> unmapped
        val unmappedRepo = seedRepo(critical = 1, ownerEmail = null)
        snapshotRepository.save(
            GithubRepoFindingSnapshot(
                githubRepositoryId = unmappedRepo.id!!,
                snapshotAt = now.minus(31, ChronoUnit.DAYS),
                criticalCount = 1, highCount = 0
            )
        )

        // Excepted -> skipped by exception
        val exceptedRepo = seedRepo(critical = 5, ownerEmail = "x@test.com")
        exceptionRepository.save(
            GithubRepoAlertException(
                githubRepositoryId = exceptedRepo.id!!,
                reason = "excepted",
                createdBy = "test"
            )
        )

        // Findings but no old-enough snapshot -> insufficient history
        val youngRepo = seedRepo(critical = 1, ownerEmail = "y@test.com")
        snapshotRepository.save(
            GithubRepoFindingSnapshot(
                githubRepositoryId = youngRepo.id!!,
                snapshotAt = now.minus(2, ChronoUnit.DAYS),
                criticalCount = 1, highCount = 0
            )
        )

        // Improved -> not alerted
        val improvedRepo = seedRepo(critical = 1, high = 0, ownerEmail = "z@test.com")
        snapshotRepository.save(
            GithubRepoFindingSnapshot(
                githubRepositoryId = improvedRepo.id!!,
                snapshotAt = now.minus(31, ChronoUnit.DAYS),
                criticalCount = 4, highCount = 2
            )
        )

        val adminToken = TestAuthHelper.getAuthToken(client, adminUser.username)
        val response = client.toBlocking().exchange(
            HttpRequest.POST(
                "/api/cli/github-repo-alerts/send",
                mapOf("dryRun" to true, "thresholdDays" to 30)
            ).bearerAuth(adminToken),
            Map::class.java
        )

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        val body = response.body()!!
        assertThat(body["status"]).isEqualTo("DRY_RUN")
        @Suppress("UNCHECKED_CAST")
        val excepted = body["reposExcepted"] as List<String>
        @Suppress("UNCHECKED_CAST")
        val unmapped = body["unmappedRepos"] as List<String>
        @Suppress("UNCHECKED_CAST")
        val skipped = body["reposSkippedInsufficientHistory"] as List<String>

        assertThat(excepted).contains(exceptedRepo.fullName)
        assertThat(unmapped).contains(unmappedRepo.fullName)
        assertThat(skipped).contains(youngRepo.fullName)
        assertThat(skipped).doesNotContain(improvedRepo.fullName)
        assertThat(unmapped).doesNotContain(alertedRepo.fullName)
        assertThat((body["emailsSent"] as Number).toInt()).isEqualTo(0)
    }

    @Test
    fun `non-admin cannot trigger the alert run`() {
        val vulnToken = TestAuthHelper.getAuthToken(client, vulnUser.username)
        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST(
                    "/api/cli/github-repo-alerts/send",
                    mapOf("dryRun" to true)
                ).bearerAuth(vulnToken),
                String::class.java
            )
        }
        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }
}
