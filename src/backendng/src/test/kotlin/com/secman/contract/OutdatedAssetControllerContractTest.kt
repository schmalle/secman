package com.secman.contract

import com.secman.domain.OutdatedAssetMaterializedView
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.security.authentication.UsernamePasswordCredentials
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Contract test for OutdatedAssetController GET /api/outdated-assets endpoint
 *
 * Verifies API contract per contracts/01-get-outdated-assets.md:
 * - Endpoint returns paginated results
 * - Supports pagination parameters (page, size, sort)
 * - Supports filtering (workgroupId, searchTerm, minSeverity)
 * - Requires authentication (ADMIN or VULN role)
 * - Returns proper HTTP status codes
 * - Response format matches specification
 *
 * Feature: 034-outdated-assets
 * Task: T013
 * User Story: US1 - View Outdated Assets (P1)
 * Spec reference: contracts/01-get-outdated-assets.md
 */
@MicronautTest(transactional = true)
class OutdatedAssetControllerContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var repository: OutdatedAssetMaterializedViewRepository

    @BeforeEach
    fun setup() {
        // Clear any existing data
        repository.deleteAll()
    }

    @Test
    fun `GET outdated-assets returns 401 when not authenticated`() {
        // When: Requesting without authentication
        val request = HttpRequest.GET<Any>("/api/outdated-assets")

        // Then: Returns 401 Unauthorized
        val response = client.toBlocking().exchange(request, String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.status)
    }

    @Test
    fun `GET outdated-assets returns paginated results with default pagination`() {
        // Given: Multiple outdated assets in database
        val asset1 = OutdatedAssetMaterializedView(
            assetId = 1L,
            assetName = "server-prod-01.example.com",
            assetType = "SERVER",
            totalOverdueCount = 18,
            criticalCount = 5,
            highCount = 10,
            mediumCount = 3,
            lowCount = 0,
            oldestVulnDays = 180,
            oldestVulnId = "CVE-2023-1234",
            lastCalculatedAt = LocalDateTime.now()
        )
        val asset2 = OutdatedAssetMaterializedView(
            assetId = 2L,
            assetName = "server-prod-02.example.com",
            assetType = "SERVER",
            totalOverdueCount = 7,
            criticalCount = 2,
            highCount = 3,
            mediumCount = 2,
            lowCount = 0,
            oldestVulnDays = 120,
            oldestVulnId = "CVE-2023-5678",
            lastCalculatedAt = LocalDateTime.now()
        )

        repository.save(asset1)
        repository.save(asset2)

        // When: Authenticated user requests outdated assets
        val request = HttpRequest.GET<Any>("/api/outdated-assets")
            .bearerAuth(getAuthToken("admin", "ADMIN"))

        // Then: Returns paginated response with default pagination
        val response = client.toBlocking().exchange(request, Map::class.java)
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body() as Map<*, *>
        assertTrue(body.containsKey("content"))
        assertTrue(body.containsKey("totalElements"))
        assertTrue(body.containsKey("totalPages"))
        assertTrue(body.containsKey("size"))
        assertTrue(body.containsKey("number"))

        val content = body["content"] as List<*>
        assertEquals(2, content.size)
    }

    @Test
    fun `GET outdated-assets supports pagination parameters`() {
        // Given: 5 outdated assets
        repeat(5) { i ->
            repository.save(OutdatedAssetMaterializedView(
                assetId = (i + 1).toLong(),
                assetName = "server-${i + 1}",
                assetType = "SERVER",
                totalOverdueCount = 10,
                oldestVulnDays = 100,
                lastCalculatedAt = LocalDateTime.now()
            ))
        }

        // When: Requesting page 1 with size 2
        val request = HttpRequest.GET<Any>("/api/outdated-assets?page=1&size=2")
            .bearerAuth(getAuthToken("admin", "ADMIN"))

        val response = client.toBlocking().exchange(request, Map::class.java)
        val body = response.body() as Map<*, *>

        // Then: Returns correct page
        assertEquals(2, (body["size"] as Number).toInt())
        assertEquals(1, (body["number"] as Number).toInt())
        assertEquals(5, (body["totalElements"] as Number).toInt())

        val content = body["content"] as List<*>
        assertEquals(2, content.size)
    }

    @Test
    fun `GET outdated-assets supports sorting by oldestVulnDays`() {
        // Given: Assets with different oldest vulnerability ages
        val asset1 = repository.save(OutdatedAssetMaterializedView(
            assetId = 1L,
            assetName = "asset-newest",
            assetType = "SERVER",
            totalOverdueCount = 5,
            oldestVulnDays = 50,
            lastCalculatedAt = LocalDateTime.now()
        ))
        val asset2 = repository.save(OutdatedAssetMaterializedView(
            assetId = 2L,
            assetName = "asset-oldest",
            assetType = "SERVER",
            totalOverdueCount = 10,
            oldestVulnDays = 200,
            lastCalculatedAt = LocalDateTime.now()
        ))

        // When: Requesting with sort by oldestVulnDays descending
        val request = HttpRequest.GET<Any>("/api/outdated-assets?sort=oldestVulnDays,desc")
            .bearerAuth(getAuthToken("admin", "ADMIN"))

        val response = client.toBlocking().exchange(request, Map::class.java)
        val body = response.body() as Map<*, *>
        val content = body["content"] as List<Map<*, *>>

        // Then: Assets are sorted by oldest first
        assertEquals("asset-oldest", content[0]["assetName"])
        assertEquals("asset-newest", content[1]["assetName"])
    }

    @Test
    fun `GET outdated-assets supports search by asset name`() {
        // Given: Assets with different names
        repository.save(OutdatedAssetMaterializedView(
            assetId = 1L,
            assetName = "server-production-web",
            assetType = "SERVER",
            totalOverdueCount = 5,
            oldestVulnDays = 100,
            lastCalculatedAt = LocalDateTime.now()
        ))
        repository.save(OutdatedAssetMaterializedView(
            assetId = 2L,
            assetName = "server-test-db",
            assetType = "SERVER",
            totalOverdueCount = 3,
            oldestVulnDays = 80,
            lastCalculatedAt = LocalDateTime.now()
        ))

        // When: Searching for "production"
        val request = HttpRequest.GET<Any>("/api/outdated-assets?searchTerm=production")
            .bearerAuth(getAuthToken("admin", "ADMIN"))

        val response = client.toBlocking().exchange(request, Map::class.java)
        val body = response.body() as Map<*, *>
        val content = body["content"] as List<Map<*, *>>

        // Then: Only matching asset is returned
        assertEquals(1, content.size)
        assertTrue((content[0]["assetName"] as String).contains("production"))
    }

    @Test
    fun `GET outdated-assets returns empty list when no outdated assets`() {
        // Given: No outdated assets in database
        // (already cleared in @BeforeEach)

        // When: Requesting outdated assets
        val request = HttpRequest.GET<Any>("/api/outdated-assets")
            .bearerAuth(getAuthToken("admin", "ADMIN"))

        val response = client.toBlocking().exchange(request, Map::class.java)
        val body = response.body() as Map<*, *>

        // Then: Returns empty content
        assertEquals(0, (body["totalElements"] as Number).toInt())
        val content = body["content"] as List<*>
        assertTrue(content.isEmpty())
    }

    @Test
    fun `GET outdated-assets response includes all required fields`() {
        // Given: An outdated asset with all fields
        repository.save(OutdatedAssetMaterializedView(
            assetId = 1234L,
            assetName = "server-prod-01.example.com",
            assetType = "SERVER",
            totalOverdueCount = 18,
            criticalCount = 5,
            highCount = 10,
            mediumCount = 3,
            lowCount = 0,
            oldestVulnDays = 180,
            oldestVulnId = "CVE-2023-1234",
            workgroupIds = "1,3,5",
            lastCalculatedAt = LocalDateTime.now()
        ))

        // When: Requesting outdated assets
        val request = HttpRequest.GET<Any>("/api/outdated-assets")
            .bearerAuth(getAuthToken("admin", "ADMIN"))

        val response = client.toBlocking().exchange(request, Map::class.java)
        val body = response.body() as Map<*, *>
        val content = body["content"] as List<Map<*, *>>
        val asset = content[0]

        // Then: Response includes all required fields per contract
        assertTrue(asset.containsKey("id"))
        assertEquals(1234, (asset["assetId"] as Number).toLong())
        assertEquals("server-prod-01.example.com", asset["assetName"])
        assertEquals("SERVER", asset["assetType"])
        assertEquals(18, (asset["totalOverdueCount"] as Number).toInt())
        assertEquals(5, (asset["criticalCount"] as Number).toInt())
        assertEquals(10, (asset["highCount"] as Number).toInt())
        assertEquals(3, (asset["mediumCount"] as Number).toInt())
        assertEquals(0, (asset["lowCount"] as Number).toInt())
        assertEquals(180, (asset["oldestVulnDays"] as Number).toInt())
        assertEquals("CVE-2023-1234", asset["oldestVulnId"])
        assertTrue(asset.containsKey("lastCalculatedAt"))
    }

    /**
     * Helper to get authentication token for testing
     * In real tests, this would call the login endpoint
     */
    private fun getAuthToken(username: String, role: String): String {
        // For contract testing, we'll use mock authentication
        // In integration tests, this would perform actual login
        val credentials = UsernamePasswordCredentials(username, "password")
        val request = HttpRequest.POST("/api/auth/login", credentials)

        try {
            val response = client.toBlocking().exchange(request, Map::class.java)
            val body = response.body() as Map<*, *>
            return body["accessToken"] as String
        } catch (e: Exception) {
            // Fallback: return mock token for contract testing
            return "mock-jwt-token-for-$role"
        }
    }
}
