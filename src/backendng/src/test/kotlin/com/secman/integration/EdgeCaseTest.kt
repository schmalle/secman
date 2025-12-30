package com.secman.integration

import com.secman.domain.User
import com.secman.dto.AddVulnerabilityRequestDto
import com.secman.dto.AddVulnerabilityResponseDto
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
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.condition.EnabledIf
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Edge case tests for CLI add-vulnerability functionality.
 * Feature: 056-test-suite (Phase 6)
 *
 * Tests boundary conditions and edge cases:
 * - Hostname formats (dots, underscores, max length)
 * - Concurrent requests for same asset
 * - Days-open edge cases
 * - CVE normalization
 */
@DisplayName("Edge Case Tests")
@EnabledIf("com.secman.testutil.DockerAvailable#isDockerAvailable")
class EdgeCaseTest : BaseIntegrationTest() {

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

    @BeforeEach
    fun setupTestUser() {
        adminUser = userRepository.save(TestDataFactory.createAdminUser(
            username = "edge-admin-${System.nanoTime()}",
            email = "edge-admin-${System.nanoTime()}@test.com"
        ))
    }

    @Nested
    @DisplayName("Hostname Format Tests (EC-001)")
    inner class HostnameFormatTests {

        @Test
        @DisplayName("EC-001a: Hostname with dots (server-01.domain.local)")
        fun `hostname_withDots`() {
            val token = TestAuthHelper.getAuthToken(client, adminUser.username)
            val hostname = "server-01.domain.local.${System.nanoTime()}"

            val request = AddVulnerabilityRequestDto(
                hostname = hostname,
                cve = "CVE-2024-DOTS",
                criticality = "HIGH",
                daysOpen = 30
            )

            val response = client.toBlocking().exchange(
                HttpRequest.POST("/api/vulnerabilities/cli-add", request).bearerAuth(token),
                AddVulnerabilityResponseDto::class.java
            )

            assertThat(response.status).isEqualTo(HttpStatus.OK)
            assertThat(response.body()!!.assetName).isEqualTo(hostname)

            val asset = assetRepository.findByNameIgnoreCase(hostname)
            assertThat(asset).isNotNull
        }

        @Test
        @DisplayName("EC-001b: Hostname with underscore (server_name)")
        fun `hostname_withUnderscore`() {
            val token = TestAuthHelper.getAuthToken(client, adminUser.username)
            val hostname = "server_name_${System.nanoTime()}"

            val request = AddVulnerabilityRequestDto(
                hostname = hostname,
                cve = "CVE-2024-UNDERSCORE",
                criticality = "MEDIUM",
                daysOpen = 20
            )

            val response = client.toBlocking().exchange(
                HttpRequest.POST("/api/vulnerabilities/cli-add", request).bearerAuth(token),
                AddVulnerabilityResponseDto::class.java
            )

            assertThat(response.status).isEqualTo(HttpStatus.OK)
            assertThat(response.body()!!.assetName).isEqualTo(hostname)

            val asset = assetRepository.findByNameIgnoreCase(hostname)
            assertThat(asset).isNotNull
        }

        @Test
        @DisplayName("EC-001c: Hostname at max length (255 chars)")
        fun `hostname_maxLength`() {
            val token = TestAuthHelper.getAuthToken(client, adminUser.username)
            // Create a hostname close to 255 chars (leaving room for uniqueness suffix)
            val baseHostname = "a".repeat(230) + "-${System.nanoTime()}"
            val hostname = baseHostname.take(255)

            val request = AddVulnerabilityRequestDto(
                hostname = hostname,
                cve = "CVE-2024-MAXLEN",
                criticality = "LOW",
                daysOpen = 10
            )

            val response = client.toBlocking().exchange(
                HttpRequest.POST("/api/vulnerabilities/cli-add", request).bearerAuth(token),
                AddVulnerabilityResponseDto::class.java
            )

            assertThat(response.status).isEqualTo(HttpStatus.OK)

            val asset = assetRepository.findByNameIgnoreCase(hostname)
            assertThat(asset).isNotNull
        }
    }

    @Nested
    @DisplayName("Concurrent Request Tests (EC-002)")
    inner class ConcurrentRequestTests {

        @Test
        @DisplayName("EC-002: Concurrent requests for same asset - no duplicates")
        fun `concurrent_sameAsset`() {
            val token = TestAuthHelper.getAuthToken(client, adminUser.username)
            val hostname = "concurrent-test-${System.nanoTime()}"

            val executor = Executors.newFixedThreadPool(5)

            val tasks = (1..5).map { i ->
                Callable {
                    val request = AddVulnerabilityRequestDto(
                        hostname = hostname,
                        cve = "CVE-2024-CONCURRENT-$i",
                        criticality = "HIGH",
                        daysOpen = 30
                    )
                    client.toBlocking().exchange(
                        HttpRequest.POST("/api/vulnerabilities/cli-add", request).bearerAuth(token),
                        AddVulnerabilityResponseDto::class.java
                    )
                }
            }

            val futures = executor.invokeAll(tasks)
            executor.shutdown()

            // Wait for all to complete
            val results = futures.map { it.get() }

            // All should succeed
            assertThat(results).allMatch { it.status == HttpStatus.OK }

            // Only one asset should exist
            val assets = assetRepository.findByNameContainingIgnoreCase(hostname)
            assertThat(assets).hasSize(1)

            // All vulnerabilities should exist
            val asset = assets[0]
            for (i in 1..5) {
                val vuln = vulnerabilityRepository.findByAssetAndVulnerabilityId(asset, "CVE-2024-CONCURRENT-$i")
                assertThat(vuln).isNotNull
            }
        }
    }

    @Nested
    @DisplayName("Days Open Edge Cases (EC-003)")
    inner class DaysOpenTests {

        @Test
        @DisplayName("EC-003: daysOpen=0 means scanTimestamp equals now")
        fun `daysOpen_zero`() {
            val token = TestAuthHelper.getAuthToken(client, adminUser.username)
            val hostname = "zero-days-${System.nanoTime()}"
            val beforeRequest = LocalDateTime.now()

            val request = AddVulnerabilityRequestDto(
                hostname = hostname,
                cve = "CVE-2024-ZERO",
                criticality = "HIGH",
                daysOpen = 0
            )

            val response = client.toBlocking().exchange(
                HttpRequest.POST("/api/vulnerabilities/cli-add", request).bearerAuth(token),
                AddVulnerabilityResponseDto::class.java
            )

            val afterRequest = LocalDateTime.now()

            assertThat(response.status).isEqualTo(HttpStatus.OK)

            val asset = assetRepository.findByNameIgnoreCase(hostname)!!
            val vuln = vulnerabilityRepository.findByAssetAndVulnerabilityId(asset, "CVE-2024-ZERO")!!

            // scanTimestamp should be approximately now (within request window)
            assertThat(vuln.scanTimestamp).isAfterOrEqualTo(beforeRequest.minusSeconds(1))
            assertThat(vuln.scanTimestamp).isBeforeOrEqualTo(afterRequest.plusSeconds(1))
            assertThat(vuln.daysOpen).isEqualTo("0 days")
        }
    }

    @Nested
    @DisplayName("CVE Normalization (EC-005)")
    inner class CveNormalizationTests {

        @Test
        @DisplayName("EC-005: Lowercase CVE is stored as provided")
        fun `cve_lowercase`() {
            val token = TestAuthHelper.getAuthToken(client, adminUser.username)
            val hostname = "cve-case-${System.nanoTime()}"

            val request = AddVulnerabilityRequestDto(
                hostname = hostname,
                cve = "cve-2024-lowercase",
                criticality = "MEDIUM",
                daysOpen = 15
            )

            val response = client.toBlocking().exchange(
                HttpRequest.POST("/api/vulnerabilities/cli-add", request).bearerAuth(token),
                AddVulnerabilityResponseDto::class.java
            )

            assertThat(response.status).isEqualTo(HttpStatus.OK)

            // Verify vulnerability exists (CVE stored as provided - normalization may vary)
            val asset = assetRepository.findByNameIgnoreCase(hostname)!!
            val vuln = vulnerabilityRepository.findByAssetAndVulnerabilityId(asset, "cve-2024-lowercase")
            assertThat(vuln).isNotNull
        }
    }
}
