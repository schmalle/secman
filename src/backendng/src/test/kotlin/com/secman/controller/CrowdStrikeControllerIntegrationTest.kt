package com.secman.controller

import com.secman.dto.CrowdStrikeQueryResponse
import com.secman.dto.CrowdStrikeVulnerabilityDto
import com.secman.service.CrowdStrikeError
import com.secman.service.CrowdStrikeQueryService
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.token.jwt.generator.JwtTokenGenerator
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
 * Integration tests for CrowdStrikeController with new query endpoint
 *
 * Tests:
 * - GET /api/vulnerabilities endpoint
 * - Query parameter validation
 * - Filtering and pagination
 * - Error handling and HTTP status mapping
 *
 * Related to: Feature 023-create-in-the (Phase 5: Backend API Integration)
 * Task: T068
 */
@MicronautTest(startApplication = false)
@DisplayName("CrowdStrikeController Integration Tests")
class CrowdStrikeControllerIntegrationTest {

    @Inject
    @field:Client("/")
    private lateinit var httpClient: HttpClient

    @Inject
    private lateinit var mockQueryService: CrowdStrikeQueryService

    @Inject
    private lateinit var jwtTokenGenerator: JwtTokenGenerator

    private val testVulnerability = CrowdStrikeVulnerabilityDto(
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
    fun mockQueryService(): CrowdStrikeQueryService = mock()

    @MockBean
    fun jwtTokenGenerator(): JwtTokenGenerator = mock()

    @BeforeEach
    fun setup() {
        // Setup default JWT token
        val auth = mock<Authentication>()
        whenever(auth.name).thenReturn("testuser")
        whenever(jwtTokenGenerator.generateToken(any())).thenReturn(Optional.of("test-jwt-token"))
    }

    @Test
    @DisplayName("GET /api/vulnerabilities should query successfully")
    fun testGetVulnerabilitiesSuccess() {
        // Given
        val hostname = "host1.example.com"
        val response = CrowdStrikeQueryResponse(
            hostname = hostname,
            vulnerabilities = listOf(testVulnerability),
            totalCount = 1,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockQueryService.queryVulnerabilities(hostname, null, null, 100))
            .thenReturn(response)

        // When
        val result = httpClient.toBlocking()
            .retrieve("/api/vulnerabilities?hostname=$hostname", CrowdStrikeQueryResponse::class.java)

        // Then
        assertNotNull(result)
        assertEquals(hostname, result.hostname)
        assertEquals(1, result.vulnerabilities.size)
    }

    @Test
    @DisplayName("GET /api/vulnerabilities with severity filter")
    fun testGetVulnerabilitiesWithSeverity() {
        // Given
        val hostname = "host1.example.com"
        val severity = "high"
        val response = CrowdStrikeQueryResponse(
            hostname = hostname,
            vulnerabilities = listOf(testVulnerability.copy(severity = "high")),
            totalCount = 1,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockQueryService.queryVulnerabilities(hostname, severity, null, 100))
            .thenReturn(response)

        // When
        val result = httpClient.toBlocking()
            .retrieve("/api/vulnerabilities?hostname=$hostname&severity=$severity", 
                CrowdStrikeQueryResponse::class.java)

        // Then
        assertNotNull(result)
        assertEquals(1, result.vulnerabilities.size)
        assertEquals("high", result.vulnerabilities[0].severity)
    }

    @Test
    @DisplayName("GET /api/vulnerabilities with product filter")
    fun testGetVulnerabilitiesWithProduct() {
        // Given
        val hostname = "host1.example.com"
        val product = "OpenSSL"
        val response = CrowdStrikeQueryResponse(
            hostname = hostname,
            vulnerabilities = listOf(testVulnerability),
            totalCount = 1,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockQueryService.queryVulnerabilities(hostname, null, product, 100))
            .thenReturn(response)

        // When
        val result = httpClient.toBlocking()
            .retrieve("/api/vulnerabilities?hostname=$hostname&product=$product",
                CrowdStrikeQueryResponse::class.java)

        // Then
        assertNotNull(result)
        assertEquals(1, result.vulnerabilities.size)
    }

    @Test
    @DisplayName("GET /api/vulnerabilities with custom limit")
    fun testGetVulnerabilitiesWithLimit() {
        // Given
        val hostname = "host1.example.com"
        val limit = 50
        val response = CrowdStrikeQueryResponse(
            hostname = hostname,
            vulnerabilities = listOf(testVulnerability),
            totalCount = 1,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockQueryService.queryVulnerabilities(hostname, null, null, limit))
            .thenReturn(response)

        // When
        val result = httpClient.toBlocking()
            .retrieve("/api/vulnerabilities?hostname=$hostname&limit=$limit",
                CrowdStrikeQueryResponse::class.java)

        // Then
        assertNotNull(result)
        assertEquals(1, result.vulnerabilities.size)
    }

    @Test
    @DisplayName("GET /api/vulnerabilities missing hostname should return 400")
    fun testGetVulnerabilitiesMissingHostname() {
        // When & Then
        val exception = assertThrows(Exception::class.java) {
            httpClient.toBlocking()
                .retrieve("/api/vulnerabilities", CrowdStrikeQueryResponse::class.java)
        }

        // Should be a 400 Bad Request or validation error
        assertTrue(exception.message?.contains("400") ?: exception.toString().contains("400"))
    }

    @Test
    @DisplayName("GET /api/vulnerabilities with invalid limit should return 400")
    fun testGetVulnerabilitiesInvalidLimit() {
        // Given
        val hostname = "host1.example.com"
        val invalidLimit = -1

        whenever(mockQueryService.queryVulnerabilities(hostname, null, null, invalidLimit))
            .thenThrow(IllegalArgumentException("Limit must be greater than 0"))

        // When & Then
        val exception = assertThrows(Exception::class.java) {
            httpClient.toBlocking()
                .retrieve("/api/vulnerabilities?hostname=$hostname&limit=$invalidLimit",
                    CrowdStrikeQueryResponse::class.java)
        }

        assertTrue(exception.toString().contains("400") || exception.message?.contains("400") ?: false)
    }

    @Test
    @DisplayName("GET /api/vulnerabilities with limit exceeding max should return 400")
    fun testGetVulnerabilitiesLimitExceedsMax() {
        // Given
        val hostname = "host1.example.com"
        val excessiveLimit = 5000

        whenever(mockQueryService.queryVulnerabilities(hostname, null, null, excessiveLimit))
            .thenThrow(IllegalArgumentException("Limit cannot exceed 1000"))

        // When & Then
        val exception = assertThrows(Exception::class.java) {
            httpClient.toBlocking()
                .retrieve("/api/vulnerabilities?hostname=$hostname&limit=$excessiveLimit",
                    CrowdStrikeQueryResponse::class.java)
        }

        assertTrue(exception.toString().contains("400") || exception.message?.contains("400") ?: false)
    }

    @Test
    @DisplayName("GET /api/vulnerabilities hostname not found should return 404")
    fun testGetVulnerabilitiesHostnameNotFound() {
        // Given
        val hostname = "nonexistent.example.com"

        whenever(mockQueryService.queryVulnerabilities(any(), any(), any(), any()))
            .thenThrow(CrowdStrikeError.NotFoundError(hostname))

        // When & Then
        val exception = assertThrows(Exception::class.java) {
            httpClient.toBlocking()
                .retrieve("/api/vulnerabilities?hostname=$hostname", 
                    CrowdStrikeQueryResponse::class.java)
        }

        assertTrue(exception.toString().contains("404") || exception.message?.contains("404") ?: false)
    }

    @Test
    @DisplayName("GET /api/vulnerabilities rate limit should return 429")
    fun testGetVulnerabilitiesRateLimit() {
        // Given
        val hostname = "host1.example.com"

        whenever(mockQueryService.queryVulnerabilities(any(), any(), any(), any()))
            .thenThrow(CrowdStrikeError.RateLimitError(retryAfterSeconds = 30))

        // When & Then
        val exception = assertThrows(Exception::class.java) {
            httpClient.toBlocking()
                .retrieve("/api/vulnerabilities?hostname=$hostname",
                    CrowdStrikeQueryResponse::class.java)
        }

        assertTrue(exception.toString().contains("429") || exception.message?.contains("429") ?: false)
    }

    @Test
    @DisplayName("GET /api/vulnerabilities configuration error should return 500")
    fun testGetVulnerabilitiesConfigurationError() {
        // Given
        val hostname = "host1.example.com"

        whenever(mockQueryService.queryVulnerabilities(any(), any(), any(), any()))
            .thenThrow(CrowdStrikeError.ConfigurationError())

        // When & Then
        val exception = assertThrows(Exception::class.java) {
            httpClient.toBlocking()
                .retrieve("/api/vulnerabilities?hostname=$hostname",
                    CrowdStrikeQueryResponse::class.java)
        }

        assertTrue(exception.toString().contains("500") || exception.message?.contains("500") ?: false)
    }

    @Test
    @DisplayName("GET /api/vulnerabilities server error should return 500")
    fun testGetVulnerabilitiesServerError() {
        // Given
        val hostname = "host1.example.com"

        whenever(mockQueryService.queryVulnerabilities(any(), any(), any(), any()))
            .thenThrow(CrowdStrikeError.ServerError())

        // When & Then
        val exception = assertThrows(Exception::class.java) {
            httpClient.toBlocking()
                .retrieve("/api/vulnerabilities?hostname=$hostname",
                    CrowdStrikeQueryResponse::class.java)
        }

        assertTrue(exception.toString().contains("500") || exception.message?.contains("500") ?: false)
    }

    @Test
    @DisplayName("GET /api/vulnerabilities with all parameters")
    fun testGetVulnerabilitiesAllParameters() {
        // Given
        val hostname = "host1.example.com"
        val severity = "high"
        val product = "OpenSSL"
        val limit = 75

        val response = CrowdStrikeQueryResponse(
            hostname = hostname,
            vulnerabilities = listOf(testVulnerability.copy(severity = "high")),
            totalCount = 1,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockQueryService.queryVulnerabilities(hostname, severity, product, limit))
            .thenReturn(response)

        // When
        val result = httpClient.toBlocking()
            .retrieve(
                "/api/vulnerabilities?hostname=$hostname&severity=$severity&product=$product&limit=$limit",
                CrowdStrikeQueryResponse::class.java
            )

        // Then
        assertNotNull(result)
        assertEquals(hostname, result.hostname)
        assertEquals(1, result.vulnerabilities.size)
    }

    @Test
    @DisplayName("GET /api/vulnerabilities with empty result")
    fun testGetVulnerabilitiesEmptyResult() {
        // Given
        val hostname = "host1.example.com"
        val response = CrowdStrikeQueryResponse(
            hostname = hostname,
            vulnerabilities = emptyList(),
            totalCount = 0,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockQueryService.queryVulnerabilities(hostname, null, null, 100))
            .thenReturn(response)

        // When
        val result = httpClient.toBlocking()
            .retrieve("/api/vulnerabilities?hostname=$hostname",
                CrowdStrikeQueryResponse::class.java)

        // Then
        assertNotNull(result)
        assertEquals(0, result.vulnerabilities.size)
    }

    @Test
    @DisplayName("GET /api/vulnerabilities should sanitize hostname")
    fun testGetVulnerabilitiesHostnameSanitization() {
        // Given
        val hostname = "host1.example.com"
        val response = CrowdStrikeQueryResponse(
            hostname = hostname,
            vulnerabilities = listOf(testVulnerability),
            totalCount = 1,
            queriedAt = LocalDateTime.now()
        )

        whenever(mockQueryService.queryVulnerabilities(hostname, null, null, 100))
            .thenReturn(response)

        // When - hostname with spaces
        val result = httpClient.toBlocking()
            .retrieve("/api/vulnerabilities?hostname=  $hostname  ",
                CrowdStrikeQueryResponse::class.java)

        // Then
        assertNotNull(result)
        assertEquals(hostname, result.hostname)
    }

    @Test
    @DisplayName("GET /api/vulnerabilities with invalid hostname format should return 400")
    fun testGetVulnerabilitiesInvalidHostname() {
        // Given
        val invalidHostname = "host@#$%1.example.com"

        whenever(mockQueryService.queryVulnerabilities(any(), any(), any(), any()))
            .thenThrow(IllegalArgumentException("Invalid hostname format"))

        // When & Then
        val exception = assertThrows(Exception::class.java) {
            httpClient.toBlocking()
                .retrieve("/api/vulnerabilities?hostname=$invalidHostname",
                    CrowdStrikeQueryResponse::class.java)
        }

        assertTrue(exception.toString().contains("400") || exception.message?.contains("400") ?: false)
    }
}
