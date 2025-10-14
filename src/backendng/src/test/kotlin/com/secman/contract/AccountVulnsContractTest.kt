package com.secman.contract

import com.secman.domain.Asset
import com.secman.domain.UserMapping
import com.secman.domain.Vulnerability
import com.secman.fixtures.AccountVulnsTestFixtures
import com.secman.repository.AssetRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import jakarta.transaction.Transactional

/**
 * Contract test for GET /api/account-vulns endpoint
 *
 * Tests API contract compliance for Account Vulns feature (018-under-vuln-management):
 * - Request: GET with JWT authentication
 * - Response: 200 OK with AccountVulnsSummaryDto
 * - Error cases: 401 Unauthorized, 403 Forbidden (admin), 404 Not Found (no mappings)
 *
 * TDD PHASE: RED - These tests MUST FAIL before implementation exists.
 *
 * Feature: User Story 1 (P1) - View Vulnerabilities for Single AWS Account
 */
@MicronautTest
@Transactional
class AccountVulnsContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userMappingRepository: UserMappingRepository

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    @BeforeEach
    fun setup() {
        // Clean up test data before each test
        vulnerabilityRepository.deleteAll()
        assetRepository.deleteAll()
        userMappingRepository.deleteAll()
    }

    @Test
    @DisplayName("GET /api/account-vulns returns 200 OK with single account data for authenticated non-admin user")
    fun testGetAccountVulnsSingleAccountSuccess() {
        // Arrange: Create test data for single AWS account
        val awsAccountId = AccountVulnsTestFixtures.TestAccountIds.PRIMARY
        val userEmail = AccountVulnsTestFixtures.TestEmails.REGULAR_USER

        // Create user mapping
        userMappingRepository.save(
            AccountVulnsTestFixtures.createUserMapping(
                email = userEmail,
                awsAccountId = awsAccountId
            )
        )

        // Create assets with vulnerabilities in account
        val asset1 = assetRepository.save(
            AccountVulnsTestFixtures.createAsset(
                name = "web-server-01",
                type = "SERVER",
                cloudAccountId = awsAccountId
            )
        )
        val asset2 = assetRepository.save(
            AccountVulnsTestFixtures.createAsset(
                name = "db-server-01",
                type = "SERVER",
                cloudAccountId = awsAccountId
            )
        )
        val asset3 = assetRepository.save(
            AccountVulnsTestFixtures.createAsset(
                name = "app-server-01",
                type = "SERVER",
                cloudAccountId = awsAccountId
            )
        )

        // Add vulnerabilities: asset1 (3), asset2 (1), asset3 (0)
        repeat(3) {
            vulnerabilityRepository.save(
                AccountVulnsTestFixtures.createVulnerability(
                    asset = asset1,
                    vulnerabilityId = "CVE-2024-000$it"
                )
            )
        }
        vulnerabilityRepository.save(
            AccountVulnsTestFixtures.createVulnerability(
                asset = asset2,
                vulnerabilityId = "CVE-2024-0010"
            )
        )

        val request = HttpRequest.GET<Any>("/api/account-vulns")
            .header("Authorization", "Bearer ${AccountVulnsTestFixtures.getValidToken()}")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert response status
        assertEquals(HttpStatus.OK, response.status, "Expected 200 OK status")

        // Assert response body structure
        val body = response.body() as Map<*, *>

        // Validate top-level structure (AccountVulnsSummaryDto)
        assertTrue(body.containsKey("accountGroups"), "Response must contain 'accountGroups'")
        assertTrue(body.containsKey("totalAssets"), "Response must contain 'totalAssets'")
        assertTrue(body.containsKey("totalVulnerabilities"), "Response must contain 'totalVulnerabilities'")

        // Validate accountGroups array
        val accountGroups = body["accountGroups"] as List<*>
        assertEquals(1, accountGroups.size, "Should have exactly 1 account group")

        // Validate account group structure
        val accountGroup = accountGroups[0] as Map<*, *>
        assertEquals(awsAccountId, accountGroup["awsAccountId"], "AWS account ID must match")
        assertTrue(accountGroup.containsKey("assets"), "Account group must contain 'assets'")
        assertTrue(accountGroup.containsKey("totalAssets"), "Account group must contain 'totalAssets'")
        assertTrue(accountGroup.containsKey("totalVulnerabilities"), "Account group must contain 'totalVulnerabilities'")

        // Validate assets array and sorting (by vulnerability count descending)
        val assets = accountGroup["assets"] as List<*>
        assertEquals(3, assets.size, "Should have 3 assets")

        // Validate first asset (highest vulnerability count)
        val firstAsset = assets[0] as Map<*, *>
        assertEquals("web-server-01", firstAsset["name"], "First asset should be web-server-01 (highest vuln count)")
        assertEquals(3, firstAsset["vulnerabilityCount"], "web-server-01 should have 3 vulnerabilities")

        // Validate second asset
        val secondAsset = assets[1] as Map<*, *>
        assertEquals("db-server-01", secondAsset["name"], "Second asset should be db-server-01")
        assertEquals(1, secondAsset["vulnerabilityCount"], "db-server-01 should have 1 vulnerability")

        // Validate third asset (no vulnerabilities)
        val thirdAsset = assets[2] as Map<*, *>
        assertEquals("app-server-01", thirdAsset["name"], "Third asset should be app-server-01")
        assertEquals(0, thirdAsset["vulnerabilityCount"], "app-server-01 should have 0 vulnerabilities")

        // Validate summary totals
        assertEquals(3, body["totalAssets"], "Total assets should be 3")
        assertEquals(4, body["totalVulnerabilities"], "Total vulnerabilities should be 4")
        assertEquals(3, accountGroup["totalAssets"], "Account group total assets should be 3")
        assertEquals(4, accountGroup["totalVulnerabilities"], "Account group total vulnerabilities should be 4")
    }

    @Test
    @DisplayName("GET /api/account-vulns without authentication returns 401 Unauthorized")
    fun testGetAccountVulnsUnauthorized() {
        // Arrange: Request without Authorization header
        val request = HttpRequest.GET<Any>("/api/account-vulns")
        // No Authorization header

        // Act & Assert
        try {
            client.toBlocking().exchange(request, Map::class.java)
            fail("Expected 401 Unauthorized when no auth token provided")
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            assertEquals(HttpStatus.UNAUTHORIZED, e.status, "Expected 401 status code")
        }
    }

    @Test
    @DisplayName("GET /api/account-vulns validates AssetVulnCountDto schema")
    fun testGetAccountVulnsResponseSchema() {
        // Arrange: Create minimal test data
        val awsAccountId = AccountVulnsTestFixtures.TestAccountIds.PRIMARY
        val userEmail = AccountVulnsTestFixtures.TestEmails.REGULAR_USER

        userMappingRepository.save(
            AccountVulnsTestFixtures.createUserMapping(
                email = userEmail,
                awsAccountId = awsAccountId
            )
        )

        val asset = assetRepository.save(
            AccountVulnsTestFixtures.createAsset(
                name = "test-asset",
                type = "SERVER",
                cloudAccountId = awsAccountId
            )
        )

        val request = HttpRequest.GET<Any>("/api/account-vulns")
            .header("Authorization", "Bearer ${AccountVulnsTestFixtures.getValidToken()}")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body() as Map<*, *>
        val accountGroups = body["accountGroups"] as List<*>
        val assets = (accountGroups[0] as Map<*, *>)["assets"] as List<*>

        // Validate AssetVulnCountDto schema for each asset
        assets.forEach { assetData ->
            val assetMap = assetData as Map<*, *>

            // Required fields
            assertTrue(assetMap.containsKey("id"), "Asset must have 'id'")
            assertTrue(assetMap.containsKey("name"), "Asset must have 'name'")
            assertTrue(assetMap.containsKey("type"), "Asset must have 'type'")
            assertTrue(assetMap.containsKey("vulnerabilityCount"), "Asset must have 'vulnerabilityCount'")

            // Type validation
            assertTrue(assetMap["id"] is Number, "'id' must be a number")
            assertTrue(assetMap["name"] is String, "'name' must be a string")
            assertTrue(assetMap["type"] is String, "'type' must be a string")
            assertTrue(assetMap["vulnerabilityCount"] is Number, "'vulnerabilityCount' must be a number")

            // Value validation
            val vulnCount = (assetMap["vulnerabilityCount"] as Number).toInt()
            assertTrue(vulnCount >= 0, "'vulnerabilityCount' must be non-negative")
        }
    }
}
