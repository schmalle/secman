package com.secman.integration

import com.secman.domain.OutdatedAssetMaterializedView
import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Integration test for workgroup-based access control
 *
 * Verifies:
 * - ADMIN users see all outdated assets
 * - VULN users see only assets from their assigned workgroups
 * - Users without workgroups see only personally owned assets
 * - Proper 403 responses for unauthorized access
 *
 * Feature: 034-outdated-assets
 * Task: T014
 * User Story: US1, US5 - Workgroup Access Control
 * Spec reference: FR-008, FR-009, US5 acceptance scenarios
 */
@MicronautTest(transactional = true)
class OutdatedAssetAccessControlTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var outdatedAssetRepository: OutdatedAssetMaterializedViewRepository

    @Inject
    lateinit var workgroupRepository: WorkgroupRepository

    @Inject
    lateinit var userRepository: UserRepository

    @BeforeEach
    fun setup() {
        // Clear test data
        outdatedAssetRepository.deleteAll()
    }

    @Test
    fun `ADMIN user sees all outdated assets regardless of workgroup`() {
        // Given: Assets from different workgroups
        val workgroup1Asset = OutdatedAssetMaterializedView(
            assetId = 1L,
            assetName = "asset-workgroup-1",
            assetType = "SERVER",
            totalOverdueCount = 5,
            oldestVulnDays = 100,
            workgroupIds = "1",  // Workgroup 1
            lastCalculatedAt = LocalDateTime.now()
        )
        val workgroup2Asset = OutdatedAssetMaterializedView(
            assetId = 2L,
            assetName = "asset-workgroup-2",
            assetType = "SERVER",
            totalOverdueCount = 3,
            oldestVulnDays = 80,
            workgroupIds = "2",  // Workgroup 2
            lastCalculatedAt = LocalDateTime.now()
        )
        val noWorkgroupAsset = OutdatedAssetMaterializedView(
            assetId = 3L,
            assetName = "asset-no-workgroup",
            assetType = "SERVER",
            totalOverdueCount = 2,
            oldestVulnDays = 60,
            workgroupIds = null,  // No workgroup
            lastCalculatedAt = LocalDateTime.now()
        )

        outdatedAssetRepository.save(workgroup1Asset)
        outdatedAssetRepository.save(workgroup2Asset)
        outdatedAssetRepository.save(noWorkgroupAsset)

        // When: ADMIN user requests outdated assets
        val request = HttpRequest.GET<Any>("/api/outdated-assets")
            .bearerAuth(getAdminToken())

        val response = client.toBlocking().exchange(request, Map::class.java)
        val body = response.body() as Map<*, *>
        val content = body["content"] as List<*>

        // Then: ADMIN sees all 3 assets
        assertEquals(HttpStatus.OK, response.status)
        assertEquals(3, content.size)
    }

    @Test
    fun `VULN user sees only assets from assigned workgroups`() {
        // Given: User assigned to Workgroup 1 only
        val workgroup1 = Workgroup(id = 1L, name = "Team A", description = "Team A")
        val workgroup2 = Workgroup(id = 2L, name = "Team B", description = "Team B")

        // Assets from different workgroups
        val workgroup1Asset = OutdatedAssetMaterializedView(
            assetId = 1L,
            assetName = "asset-team-a",
            assetType = "SERVER",
            totalOverdueCount = 5,
            oldestVulnDays = 100,
            workgroupIds = "1",  // Workgroup 1
            lastCalculatedAt = LocalDateTime.now()
        )
        val workgroup2Asset = OutdatedAssetMaterializedView(
            assetId = 2L,
            assetName = "asset-team-b",
            assetType = "SERVER",
            totalOverdueCount = 3,
            oldestVulnDays = 80,
            workgroupIds = "2",  // Workgroup 2 (user should NOT see this)
            lastCalculatedAt = LocalDateTime.now()
        )

        outdatedAssetRepository.save(workgroup1Asset)
        outdatedAssetRepository.save(workgroup2Asset)

        // When: VULN user (assigned to Workgroup 1) requests outdated assets
        val request = HttpRequest.GET<Any>("/api/outdated-assets")
            .bearerAuth(getVulnToken(workgroupIds = listOf(1L)))

        val response = client.toBlocking().exchange(request, Map::class.java)
        val body = response.body() as Map<*, *>
        val content = body["content"] as List<Map<*, *>>

        // Then: VULN user sees only Workgroup 1 asset
        assertEquals(HttpStatus.OK, response.status)
        assertEquals(1, content.size)
        assertEquals("asset-team-a", content[0]["assetName"])
    }

    @Test
    fun `VULN user with multiple workgroups sees assets from all assigned workgroups`() {
        // Given: User assigned to Workgroups 1 and 3
        val asset1 = OutdatedAssetMaterializedView(
            assetId = 1L,
            assetName = "asset-wg1",
            assetType = "SERVER",
            totalOverdueCount = 5,
            oldestVulnDays = 100,
            workgroupIds = "1",
            lastCalculatedAt = LocalDateTime.now()
        )
        val asset2 = OutdatedAssetMaterializedView(
            assetId = 2L,
            assetName = "asset-wg2",
            assetType = "SERVER",
            totalOverdueCount = 3,
            oldestVulnDays = 80,
            workgroupIds = "2",  // Should NOT see this
            lastCalculatedAt = LocalDateTime.now()
        )
        val asset3 = OutdatedAssetMaterializedView(
            assetId = 3L,
            assetName = "asset-wg3",
            assetType = "SERVER",
            totalOverdueCount = 7,
            oldestVulnDays = 120,
            workgroupIds = "3",
            lastCalculatedAt = LocalDateTime.now()
        )

        outdatedAssetRepository.save(asset1)
        outdatedAssetRepository.save(asset2)
        outdatedAssetRepository.save(asset3)

        // When: VULN user (assigned to Workgroups 1 and 3) requests assets
        val request = HttpRequest.GET<Any>("/api/outdated-assets")
            .bearerAuth(getVulnToken(workgroupIds = listOf(1L, 3L)))

        val response = client.toBlocking().exchange(request, Map::class.java)
        val body = response.body() as Map<*, *>
        val content = body["content"] as List<Map<*, *>>

        // Then: User sees assets from Workgroups 1 and 3 only
        assertEquals(2, content.size)
        val assetNames = content.map { it["assetName"] as String }.toSet()
        assertTrue(assetNames.contains("asset-wg1"))
        assertTrue(assetNames.contains("asset-wg3"))
        assertFalse(assetNames.contains("asset-wg2"))
    }

    @Test
    fun `VULN user with no workgroups sees assets without workgroup assignments`() {
        // Given: Mix of assets with and without workgroups
        val workgroupAsset = OutdatedAssetMaterializedView(
            assetId = 1L,
            assetName = "asset-with-workgroup",
            assetType = "SERVER",
            totalOverdueCount = 5,
            oldestVulnDays = 100,
            workgroupIds = "1",
            lastCalculatedAt = LocalDateTime.now()
        )
        val noWorkgroupAsset = OutdatedAssetMaterializedView(
            assetId = 2L,
            assetName = "asset-no-workgroup",
            assetType = "SERVER",
            totalOverdueCount = 3,
            oldestVulnDays = 80,
            workgroupIds = null,  // No workgroup
            lastCalculatedAt = LocalDateTime.now()
        )

        outdatedAssetRepository.save(workgroupAsset)
        outdatedAssetRepository.save(noWorkgroupAsset)

        // When: VULN user with no workgroups requests assets
        val request = HttpRequest.GET<Any>("/api/outdated-assets")
            .bearerAuth(getVulnToken(workgroupIds = emptyList()))

        val response = client.toBlocking().exchange(request, Map::class.java)
        val body = response.body() as Map<*, *>
        val content = body["content"] as List<Map<*, *>>

        // Then: User sees only assets without workgroup assignments
        assertEquals(1, content.size)
        assertEquals("asset-no-workgroup", content[0]["assetName"])
    }

    @Test
    fun `asset belonging to multiple workgroups is visible if user has access to any of them`() {
        // Given: Asset belongs to Workgroups 1, 2, and 3
        val multiWorkgroupAsset = OutdatedAssetMaterializedView(
            assetId = 1L,
            assetName = "asset-multi-workgroup",
            assetType = "SERVER",
            totalOverdueCount = 5,
            oldestVulnDays = 100,
            workgroupIds = "1,2,3",  // Multiple workgroups
            lastCalculatedAt = LocalDateTime.now()
        )

        outdatedAssetRepository.save(multiWorkgroupAsset)

        // When: VULN user assigned to Workgroup 2 only
        val request = HttpRequest.GET<Any>("/api/outdated-assets")
            .bearerAuth(getVulnToken(workgroupIds = listOf(2L)))

        val response = client.toBlocking().exchange(request, Map::class.java)
        val body = response.body() as Map<*, *>
        val content = body["content"] as List<Map<*, *>>

        // Then: User sees the asset (has access via Workgroup 2)
        assertEquals(1, content.size)
        assertEquals("asset-multi-workgroup", content[0]["assetName"])
    }

    @Test
    fun `USER role without VULN or ADMIN cannot access outdated assets`() {
        // When: USER role (not VULN or ADMIN) tries to access endpoint
        val request = HttpRequest.GET<Any>("/api/outdated-assets")
            .bearerAuth(getUserToken())

        // Then: Returns 403 Forbidden
        try {
            client.toBlocking().exchange(request, Map::class.java)
            fail("Expected 403 Forbidden")
        } catch (e: Exception) {
            // Expected exception for 403
            assertTrue(e.message?.contains("403") == true || e.message?.contains("Forbidden") == true)
        }
    }

    /**
     * Helper methods to generate auth tokens for different roles
     * In real implementation, these would create actual JWT tokens
     */
    private fun getAdminToken(): String {
        return "mock-admin-token"
    }

    private fun getVulnToken(workgroupIds: List<Long>): String {
        return "mock-vuln-token-wg-${workgroupIds.joinToString(",")}"
    }

    private fun getUserToken(): String {
        return "mock-user-token"
    }
}
