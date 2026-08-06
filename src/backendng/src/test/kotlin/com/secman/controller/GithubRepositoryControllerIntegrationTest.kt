package com.secman.controller

import com.secman.domain.GithubRepoAlertException
import com.secman.domain.GithubRepoDependabotAlert
import com.secman.domain.GithubRepoFindingSnapshot
import com.secman.domain.GithubRepository
import com.secman.domain.User
import com.secman.repository.GithubRepoAlertExceptionRepository
import com.secman.repository.GithubRepoDependabotAlertRepository
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
import org.assertj.core.api.Assertions.assertThatThrownBy
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
 * - GET/POST/PUT/DELETE /api/github/owner-email-mappings (ADMIN/VULN/SECCHAMPION read; ADMIN/VULN write)
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

    @Inject
    lateinit var alertRepository: GithubRepoDependabotAlertRepository

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
        return seedRepoNamed("repo-$suffix", critical, high, ownerEmail)
    }

    private fun seedRepoNamed(name: String, critical: Int = 0, high: Int = 0, ownerEmail: String? = null): GithubRepository {
        return githubRepositoryRepository.save(
            GithubRepository(
                githubRepoId = System.nanoTime(),
                name = name,
                owner = "test-org",
                fullName = "test-org/$name",
                htmlUrl = "https://github.com/test-org/$name",
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
            HttpRequest.GET<Any>("/api/github/repositories?search=${repo.fullName}").bearerAuth(champToken),
            Map::class.java
        )
        assertThat(response.status).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val content = response.body()!!["content"] as List<Map<String, Any?>>
        val row = content.first { (it["id"] as Number).toLong() == repo.id }
        assertThat((row["criticalCount"] as Number).toInt()).isEqualTo(3)
        assertThat((row["highCount"] as Number).toInt()).isEqualTo(1)
        assertThat(row["activeException"]).isNull()

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
    fun `paginates and defaults to critical desc, high desc, name asc sort`() {
        val marker = "pg-${System.nanoTime()}"
        val repoA = seedRepoNamed("$marker-a", critical = 5, high = 2)
        val repoB = seedRepoNamed("$marker-b", critical = 2, high = 9)
        val repoC = seedRepoNamed("$marker-c", critical = 2, high = 1)
        val repoD = seedRepoNamed("$marker-d", critical = 0, high = 1)

        val adminToken = TestAuthHelper.getAuthToken(client, adminUser.username)

        val page0 = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/github/repositories?search=$marker&page=0&size=2").bearerAuth(adminToken),
            Map::class.java
        ).body()!!
        @Suppress("UNCHECKED_CAST")
        val content0 = page0["content"] as List<Map<String, Any?>>
        assertThat(content0.map { it["fullName"] }).containsExactly(repoA.fullName, repoB.fullName)
        assertThat((page0["totalElements"] as Number).toLong()).isEqualTo(4)
        assertThat((page0["totalPages"] as Number).toInt()).isEqualTo(2)
        assertThat((page0["number"] as Number).toInt()).isEqualTo(0)

        val page1 = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/github/repositories?search=$marker&page=1&size=2").bearerAuth(adminToken),
            Map::class.java
        ).body()!!
        @Suppress("UNCHECKED_CAST")
        val content1 = page1["content"] as List<Map<String, Any?>>
        assertThat(content1.map { it["fullName"] }).containsExactly(repoC.fullName, repoD.fullName)

        val searchOne = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/github/repositories?search=$marker-c").bearerAuth(adminToken),
            Map::class.java
        ).body()!!
        @Suppress("UNCHECKED_CAST")
        val contentSearch = searchOne["content"] as List<Map<String, Any?>>
        assertThat(contentSearch.map { it["fullName"] }).containsExactly(repoC.fullName)
    }

    @Test
    fun `summary totals cover all repositories and are denied to regular users`() {
        val adminToken = TestAuthHelper.getAuthToken(client, adminUser.username)

        val before = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/github/repositories/summary").bearerAuth(adminToken),
            Map::class.java
        ).body()!!
        val criticalBefore = (before["criticalTotal"] as Number).toLong()
        val highBefore = (before["highTotal"] as Number).toLong()
        val countBefore = (before["totalCount"] as Number).toLong()

        seedRepo(critical = 7, high = 3)

        val after = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/github/repositories/summary").bearerAuth(adminToken),
            Map::class.java
        ).body()!!
        assertThat((after["criticalTotal"] as Number).toLong()).isEqualTo(criticalBefore + 7)
        assertThat((after["highTotal"] as Number).toLong()).isEqualTo(highBefore + 3)
        assertThat((after["totalCount"] as Number).toLong()).isEqualTo(countBefore + 1)

        val userToken = TestAuthHelper.getAuthToken(client, regularUser.username)
        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/github/repositories/summary").bearerAuth(userToken),
                String::class.java
            )
        }
        assertThat(ex.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `lists a repository's alerts for SECCHAMPION, denies regular user, 404s for unknown id`() {
        val repo = seedRepo(critical = 1, high = 0)
        alertRepository.save(
            GithubRepoDependabotAlert(
                githubRepositoryId = repo.id!!,
                alertNumber = 1,
                packageName = "lodash",
                ecosystem = "npm",
                severity = "critical",
                cveId = "CVE-2024-1"
            )
        )

        val champToken = TestAuthHelper.getAuthToken(client, champUser.username)
        val response = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/github/repositories/${repo.id}/alerts")
                .header("Authorization", "Bearer $champToken"),
            Array<Any>::class.java
        )
        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat(response.body()).hasSize(1)
        @Suppress("UNCHECKED_CAST")
        val alert = response.body()!![0] as Map<String, Any?>
        assertThat(alert["packageName"]).isEqualTo("lodash")

        val regularToken = TestAuthHelper.getAuthToken(client, regularUser.username)
        assertThatThrownBy {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/github/repositories/${repo.id}/alerts")
                    .header("Authorization", "Bearer $regularToken"),
                String::class.java
            )
        }.isInstanceOf(HttpClientResponseException::class.java)
            .satisfies({ e -> assertThat((e as HttpClientResponseException).status).isEqualTo(HttpStatus.FORBIDDEN) })

        assertThatThrownBy {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/github/repositories/999999999/alerts")
                    .header("Authorization", "Bearer $champToken"),
                String::class.java
            )
        }.isInstanceOf(HttpClientResponseException::class.java)
            .satisfies({ e -> assertThat((e as HttpClientResponseException).status).isEqualTo(HttpStatus.NOT_FOUND) })
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
            HttpRequest.GET<Any>("/api/github/repositories?search=${repo.fullName}").bearerAuth(adminToken),
            Map::class.java
        )
        @Suppress("UNCHECKED_CAST")
        val content = listResponse.body()!!["content"] as List<Map<String, Any?>>
        val row = content.first { (it["id"] as Number).toLong() == repo.id }
        assertThat(row["activeException"]).isNotNull()
        @Suppress("UNCHECKED_CAST")
        val activeException = row["activeException"] as Map<String, Any?>
        assertThat(activeException["reason"]).isEqualTo("accepted risk for integration test")

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

    @Test
    fun `VULN creates an owner email mapping, backfills blank repos, SECCHAMPION cannot write`() {
        val owner = "owner-mapping-org-${System.nanoTime()}"
        val blankRepo = seedRepoNamed("bf-${System.nanoTime()}-a", ownerEmail = null).also {
            it.owner = owner
            githubRepositoryRepository.update(it)
        }
        val manualRepo = seedRepoNamed("bf-${System.nanoTime()}-b", ownerEmail = "manual@example.com").also {
            it.owner = owner
            githubRepositoryRepository.update(it)
        }

        val vulnToken = TestAuthHelper.getAuthToken(client, vulnUser.username)
        val created = client.toBlocking().exchange(
            HttpRequest.POST(
                "/api/github/owner-email-mappings",
                mapOf("owner" to owner, "email" to "Default@Example.COM")
            ).bearerAuth(vulnToken),
            Map::class.java
        )
        assertThat(created.status).isEqualTo(HttpStatus.CREATED)
        val body = created.body()!!
        assertThat(body["email"]).isEqualTo("default@example.com")
        assertThat((body["repoCount"] as Number).toLong()).isEqualTo(2)
        val mappingId = (body["id"] as Number).toLong()

        assertThat(githubRepositoryRepository.findById(blankRepo.id!!).get().ownerEmail).isEqualTo("default@example.com")
        assertThat(githubRepositoryRepository.findById(manualRepo.id!!).get().ownerEmail).isEqualTo("manual@example.com")

        // Duplicate owner rejected
        val dupEx = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST(
                    "/api/github/owner-email-mappings",
                    mapOf("owner" to owner, "email" to "other@example.com")
                ).bearerAuth(vulnToken),
                String::class.java
            )
        }
        assertThat(dupEx.status).isEqualTo(HttpStatus.CONFLICT)

        // Invalid email rejected
        val invalidEx = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST(
                    "/api/github/owner-email-mappings",
                    mapOf("owner" to "another-${System.nanoTime()}", "email" to "not-an-email")
                ).bearerAuth(vulnToken),
                String::class.java
            )
        }
        assertThat(invalidEx.status).isEqualTo(HttpStatus.BAD_REQUEST)

        // SECCHAMPION can read (list) but cannot write
        val champToken = TestAuthHelper.getAuthToken(client, champUser.username)
        val list = client.toBlocking().exchange(
            HttpRequest.GET<Any>("/api/github/owner-email-mappings").bearerAuth(champToken),
            Array<Any>::class.java
        )
        assertThat(list.status).isEqualTo(HttpStatus.OK)

        val champWriteEx = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.PUT(
                    "/api/github/owner-email-mappings/$mappingId",
                    mapOf("email" to "x@y.zz")
                ).bearerAuth(champToken),
                String::class.java
            )
        }
        assertThat(champWriteEx.status).isEqualTo(HttpStatus.FORBIDDEN)

        // VULN can update
        val updated = client.toBlocking().exchange(
            HttpRequest.PUT(
                "/api/github/owner-email-mappings/$mappingId",
                mapOf("email" to "updated@example.com")
            ).bearerAuth(vulnToken),
            Map::class.java
        )
        assertThat(updated.status).isEqualTo(HttpStatus.OK)
        assertThat(updated.body()!!["email"]).isEqualTo("updated@example.com")

        // Manual repo still untouched after update backfill re-run
        assertThat(githubRepositoryRepository.findById(manualRepo.id!!).get().ownerEmail).isEqualTo("manual@example.com")

        // VULN can delete; regular user denied entirely
        val regularToken = TestAuthHelper.getAuthToken(client, regularUser.username)
        val regularEx = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/github/owner-email-mappings").bearerAuth(regularToken),
                String::class.java
            )
        }
        assertThat(regularEx.status).isEqualTo(HttpStatus.FORBIDDEN)

        val deleted = client.toBlocking().exchange(
            HttpRequest.DELETE<Any>("/api/github/owner-email-mappings/$mappingId").bearerAuth(vulnToken),
            String::class.java
        )
        assertThat(deleted.status).isEqualTo(HttpStatus.NO_CONTENT)

        // Deleting the mapping does not un-set the already-backfilled repo email
        assertThat(githubRepositoryRepository.findById(blankRepo.id!!).get().ownerEmail).isEqualTo("updated@example.com")
    }

    @Test
    fun `owner email discovery is denied to SECCHAMPION and regular users`() {
        val champToken = TestAuthHelper.getAuthToken(client, champUser.username)
        val champEx = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST(
                    "/api/github/owner-email-mappings/discover",
                    mapOf("dryRun" to true)
                ).bearerAuth(champToken),
                String::class.java
            )
        }
        assertThat(champEx.status).isEqualTo(HttpStatus.FORBIDDEN)

        val regularToken = TestAuthHelper.getAuthToken(client, regularUser.username)
        val regularEx = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.POST(
                    "/api/github/owner-email-mappings/discover",
                    mapOf("dryRun" to true)
                ).bearerAuth(regularToken),
                String::class.java
            )
        }
        assertThat(regularEx.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `owner email mapping 404s for unknown id`() {
        val vulnToken = TestAuthHelper.getAuthToken(client, vulnUser.username)
        val ex = org.junit.jupiter.api.assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(
                HttpRequest.PUT(
                    "/api/github/owner-email-mappings/999999999",
                    mapOf("email" to "x@y.zz")
                ).bearerAuth(vulnToken),
                String::class.java
            )
        }
        assertThat(ex.status).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
