package com.secman.service

import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse as SharedQueryResponse
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto as SharedVulnerabilityDto
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.exception.NotFoundException
import com.secman.crowdstrike.exception.RateLimitException
import com.secman.domain.FalconConfig
import com.secman.repository.FalconConfigRepository
import io.micronaut.test.annotation.MockBean
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import java.time.LocalDateTime
import java.util.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.Assertions.*

/**
 * Contract tests for CrowdStrikeQueryService
 *
 * Tests interaction with:
 * - CrowdStrikeApiClient (shared module)
 * - FalconConfigRepository (backend repository)
 * - Error handling and filtering
 *
 * Related to: Feature 023-create-in-the (Phase 5: Backend API Integration)
 * Task: T067
 */
@MicronautTest
@DisplayName("CrowdStrikeQueryService Contract Tests")
class CrowdStrikeQueryServiceContractTest {

    @Inject
    private lateinit var service: CrowdStrikeQueryService

    @Inject
    private lateinit var mockApiClient: CrowdStrikeApiClient

    @Inject
    private lateinit var mockConfigRepository: FalconConfigRepository

    private val testConfig = FalconConfig(
        id = 1L,
        clientId = "test-client-id",
        clientSecret = "test-secret",
        cloudRegion = "us-1",
        isActive = true
    )

    private val testVulnerability = SharedVulnerabilityDto(
        id = "vuln-001",
        hostname = "host1.example.com",
        ip = "192.168.1.1",
        cveId = "CVE-2024-1234",
        severity = "critical",
        cvssScore = 9.5,
        affectedProduct = "Apache OpenSSL 1.1.1",
        daysOpen = "5 days",
        detectedAt = LocalDateTime.now().minusDays(5),
        status = "open",
        hasException = false
    )

    @MockBean
    fun mockApiClient(): CrowdStrikeApiClient = mock()

    @MockBean
    fun mockConfigRepository(): FalconConfigRepository = mock()

    @BeforeEach
    fun setup() {
        // Default configuration mock
        whenever(mockConfigRepository.findActiveConfig())
            .thenReturn(Optional.of(testConfig))
    }

    @Test
    @DisplayName("Should query vulnerabilities successfully")
    fun testQueryVulnerabilitiesSuccess() {
        // Given
        val hostname = "host1.example.com"
        val response = SharedQueryResponse(
            hostname = hostname,
            vulnerabilities = listOf(testVulnerability),
            totalCount = 1,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockApiClient.queryAllVulnerabilities(any(), any(), any()))
            .thenReturn(response)

        // When
        val result = service.queryVulnerabilities(hostname)

        // Then
        assertNotNull(result)
        assertEquals(hostname, result.hostname)
        assertEquals(1, result.vulnerabilities.size)
        assertEquals("vuln-001", result.vulnerabilities[0].id)
        assertEquals("critical", result.vulnerabilities[0].severity)
    }

    @Test
    @DisplayName("Should filter vulnerabilities by severity")
    fun testQueryVulnerabilitiesWithSeverityFilter() {
        // Given
        val hostname = "host1.example.com"
        val severity = "high"

        val high1 = testVulnerability.copy(id = "vuln-001", severity = "high")
        val high2 = testVulnerability.copy(id = "vuln-002", severity = "high")
        val critical = testVulnerability.copy(id = "vuln-003", severity = "critical")

        val response = SharedQueryResponse(
            hostname = hostname,
            vulnerabilities = listOf(high1, high2, critical),
            totalCount = 3,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockApiClient.queryAllVulnerabilities(any(), any(), any()))
            .thenReturn(response)

        // When
        val result = service.queryVulnerabilities(hostname, severity = severity)

        // Then
        assertEquals(2, result.vulnerabilities.size)
        result.vulnerabilities.forEach { vuln ->
            assertEquals("high", vuln.severity.lowercase())
        }
    }

    @Test
    @DisplayName("Should filter vulnerabilities by severity (case-insensitive)")
    fun testQueryVulnerabilitiesSeverityFilterCaseInsensitive() {
        // Given
        val hostname = "host1.example.com"
        val severity = "CRITICAL"

        val critical = testVulnerability.copy(id = "vuln-001", severity = "critical")

        val response = SharedQueryResponse(
            hostname = hostname,
            vulnerabilities = listOf(critical),
            totalCount = 1,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockApiClient.queryAllVulnerabilities(any(), any(), any()))
            .thenReturn(response)

        // When
        val result = service.queryVulnerabilities(hostname, severity = severity)

        // Then
        assertEquals(1, result.vulnerabilities.size)
        assertEquals("critical", result.vulnerabilities[0].severity)
    }

    @Test
    @DisplayName("Should filter vulnerabilities by product")
    fun testQueryVulnerabilitiesWithProductFilter() {
        // Given
        val hostname = "host1.example.com"
        val product = "OpenSSL"

        val openssl = testVulnerability.copy(id = "vuln-001", affectedProduct = "Apache OpenSSL 1.1.1")
        val apache = testVulnerability.copy(id = "vuln-002", affectedProduct = "Apache HTTP Server")
        val other = testVulnerability.copy(id = "vuln-003", affectedProduct = "Other Software")

        val response = SharedQueryResponse(
            hostname = hostname,
            vulnerabilities = listOf(openssl, apache, other),
            totalCount = 3,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockApiClient.queryAllVulnerabilities(any(), any(), any()))
            .thenReturn(response)

        // When
        val result = service.queryVulnerabilities(hostname, product = product)

        // Then
        assertEquals(2, result.vulnerabilities.size) // OpenSSL and Apache match
        result.vulnerabilities.forEach { vuln ->
            assertTrue(vuln.affectedProduct?.contains(product, ignoreCase = true) ?: false)
        }
    }

    @Test
    @DisplayName("Should apply both severity and product filters")
    fun testQueryVulnerabilitiesWithBothFilters() {
        // Given
        val hostname = "host1.example.com"
        val severity = "high"
        val product = "OpenSSL"

        val highOpenssl = testVulnerability.copy(
            id = "vuln-001",
            severity = "high",
            affectedProduct = "Apache OpenSSL 1.1.1"
        )
        val highApache = testVulnerability.copy(
            id = "vuln-002",
            severity = "high",
            affectedProduct = "Apache HTTP Server"
        )
        val criticalOpenssl = testVulnerability.copy(
            id = "vuln-003",
            severity = "critical",
            affectedProduct = "OpenSSL 3.0"
        )

        val response = SharedQueryResponse(
            hostname = hostname,
            vulnerabilities = listOf(highOpenssl, highApache, criticalOpenssl),
            totalCount = 3,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockApiClient.queryAllVulnerabilities(any(), any(), any()))
            .thenReturn(response)

        // When
        val result = service.queryVulnerabilities(hostname, severity = severity, product = product)

        // Then
        assertEquals(1, result.vulnerabilities.size) // Only highOpenssl matches both filters
        assertEquals("vuln-001", result.vulnerabilities[0].id)
        assertEquals("high", result.vulnerabilities[0].severity)
        assertTrue(result.vulnerabilities[0].affectedProduct?.contains(product) ?: false)
    }

    @Test
    @DisplayName("Should handle hostname not found error")
    fun testQueryVulnerabilitiesNotFound() {
        // Given
        val hostname = "nonexistent.example.com"

        whenever(mockApiClient.queryAllVulnerabilities(any(), any(), any()))
            .thenThrow(NotFoundException("System not found"))

        // When & Then
        val exception = assertThrows(CrowdStrikeError.NotFoundError::class.java) {
            service.queryVulnerabilities(hostname)
        }

        assertTrue(exception.message?.contains(hostname) ?: false)
    }

    @Test
    @DisplayName("Should handle rate limit error")
    fun testQueryVulnerabilitiesRateLimit() {
        // Given
        val hostname = "host1.example.com"

        whenever(mockApiClient.queryAllVulnerabilities(any(), any(), any()))
            .thenThrow(RateLimitException("Rate limit exceeded"))

        // When & Then
        val exception = assertThrows(CrowdStrikeError.RateLimitError::class.java) {
            service.queryVulnerabilities(hostname)
        }

        assertNotNull(exception.retryAfterSeconds)
        assertEquals(60, exception.retryAfterSeconds)
    }

    @Test
    @DisplayName("Should handle configuration not found")
    fun testQueryVulnerabilitiesNoConfiguration() {
        // Given
        val hostname = "host1.example.com"

        whenever(mockConfigRepository.findActiveConfig())
            .thenReturn(Optional.empty())

        // When & Then
        val exception = assertThrows(CrowdStrikeError.ConfigurationError::class.java) {
            service.queryVulnerabilities(hostname)
        }

        assertTrue(exception.message?.contains("not configured") ?: false)
    }

    @Test
    @DisplayName("Should respect limit parameter")
    fun testQueryVulnerabilitiesWithLimit() {
        // Given
        val hostname = "host1.example.com"
        val limit = 50

        val response = SharedQueryResponse(
            hostname = hostname,
            vulnerabilities = listOf(testVulnerability),
            totalCount = 1,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockApiClient.queryAllVulnerabilities(hostname, any(), limit))
            .thenReturn(response)

        // When
        val result = service.queryVulnerabilities(hostname, limit = limit)

        // Then
        assertNotNull(result)
        assertEquals(1, result.vulnerabilities.size)
    }

    @Test
    @DisplayName("Should map cloud region to base URL")
    fun testMapCloudRegionToBaseUrl() {
        // Given
        val regionTests = listOf(
            "us-1" to "https://api.crowdstrike.com",
            "us-2" to "https://api.us-2.crowdstrike.com",
            "eu-1" to "https://api.eu-1.crowdstrike.com",
            "us-gov-1" to "https://api.us-gov-1.crowdstrike.com",
            "us-gov-2" to "https://api.us-gov-2.crowdstrike.com"
        )

        for ((region, expectedUrl) in regionTests) {
            // When
            val config = FalconConfig(
                id = 1L,
                clientId = "test",
                clientSecret = "test",
                cloudRegion = region,
                isActive = true
            )

            whenever(mockConfigRepository.findActiveConfig())
                .thenReturn(Optional.of(config))

            val response = SharedQueryResponse(
                hostname = "host1.example.com",
                vulnerabilities = listOf(testVulnerability),
                totalCount = 1,
                queriedAt = LocalDateTime.now()
            )

            whenever(mockApiClient.queryAllVulnerabilities(any(), any(), any()))
                .thenReturn(response)

            // Then - just verify it doesn't throw and returns correctly
            val result = service.queryVulnerabilities("host1.example.com")
            assertNotNull(result)
        }
    }

    @Test
    @DisplayName("Should return empty vulnerabilities list when no matches found")
    fun testQueryVulnerabilitiesEmptyResult() {
        // Given
        val hostname = "host1.example.com"

        val response = SharedQueryResponse(
            hostname = hostname,
            vulnerabilities = emptyList(),
            totalCount = 0,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockApiClient.queryAllVulnerabilities(any(), any(), any()))
            .thenReturn(response)

        // When
        val result = service.queryVulnerabilities(hostname)

        // Then
        assertNotNull(result)
        assertEquals(0, result.vulnerabilities.size)
        assertEquals(0, result.totalCount)
    }

    @Test
    @DisplayName("Should filter empty when product matches no vulnerabilities")
    fun testQueryVulnerabilitiesProductFilterNoMatch() {
        // Given
        val hostname = "host1.example.com"
        val product = "NonExistentProduct"

        val response = SharedQueryResponse(
            hostname = hostname,
            vulnerabilities = listOf(testVulnerability),
            totalCount = 1,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockApiClient.queryAllVulnerabilities(any(), any(), any()))
            .thenReturn(response)

        // When
        val result = service.queryVulnerabilities(hostname, product = product)

        // Then
        assertEquals(0, result.vulnerabilities.size)
    }

    @Test
    @DisplayName("Should handle blank hostname")
    fun testQueryVulnerabilitiesBlankHostname() {
        // When & Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.queryVulnerabilities("")
        }

        assertTrue(exception.message?.contains("blank") ?: false)
    }
}
