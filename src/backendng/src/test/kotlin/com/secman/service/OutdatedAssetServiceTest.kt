package com.secman.service

import com.secman.domain.OutdatedAssetMaterializedView
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Unit tests for OutdatedAssetService
 *
 * Tests:
 * - Workgroup filtering logic
 * - ADMIN vs VULN role access patterns
 * - Search and severity filtering
 * - Pagination support
 * - Workgroup ID extraction from authentication
 *
 * Feature: 034-outdated-assets
 * Task: T017
 * User Story: US1 - View Outdated Assets (P1)
 * Spec reference: FR-008, FR-009
 */
class OutdatedAssetServiceTest {

    private lateinit var service: OutdatedAssetService
    private lateinit var repository: OutdatedAssetMaterializedViewRepository

    @BeforeEach
    fun setup() {
        repository = mockk()
        service = OutdatedAssetService(repository)
    }

    @Test
    fun `ADMIN user sees all outdated assets without workgroup filtering`() {
        // Given: ADMIN user authentication
        val authentication = mockk<Authentication>()
        every { authentication.roles } returns setOf("ADMIN", "USER")
        every { authentication.attributes } returns mapOf<String, Any>()

        val pageable = Pageable.from(0, 20)
        val expectedPage = mockk<Page<OutdatedAssetMaterializedView>>()

        every {
            repository.findOutdatedAssets(
                workgroupId = null,  // ADMIN should pass null for no filtering
                searchTerm = null,
                minSeverity = null,
                pageable = pageable
            )
        } returns expectedPage

        // When: ADMIN requests outdated assets
        val result = service.getOutdatedAssets(authentication, pageable = pageable)

        // Then: Repository called with null workgroupId (no filtering)
        assertEquals(expectedPage, result)
        verify(exactly = 1) {
            repository.findOutdatedAssets(
                workgroupId = null,
                searchTerm = null,
                minSeverity = null,
                pageable = pageable
            )
        }
    }

    @Test
    fun `VULN user sees only assets from assigned workgroups`() {
        // Given: VULN user assigned to workgroups 1 and 3
        val authentication = mockk<Authentication>()
        every { authentication.roles } returns setOf("VULN", "USER")
        every { authentication.attributes } returns mapOf(
            "workgroupIds" to listOf(1L, 3L)
        )

        val pageable = Pageable.from(0, 20)
        val expectedPage = mockk<Page<OutdatedAssetMaterializedView>>()

        every {
            repository.findOutdatedAssets(
                workgroupId = "1,3",  // Comma-separated workgroup IDs
                searchTerm = null,
                minSeverity = null,
                pageable = pageable
            )
        } returns expectedPage

        // When: VULN user requests outdated assets
        val result = service.getOutdatedAssets(authentication, pageable = pageable)

        // Then: Repository called with user's workgroup IDs
        assertEquals(expectedPage, result)
        verify(exactly = 1) {
            repository.findOutdatedAssets(
                workgroupId = "1,3",
                searchTerm = null,
                minSeverity = null,
                pageable = pageable
            )
        }
    }

    @Test
    fun `VULN user with single workgroup filters correctly`() {
        // Given: VULN user assigned to single workgroup
        val authentication = mockk<Authentication>()
        every { authentication.roles } returns setOf("VULN")
        every { authentication.attributes } returns mapOf(
            "workgroupIds" to listOf(2L)
        )

        val pageable = Pageable.from(0, 20)
        val expectedPage = mockk<Page<OutdatedAssetMaterializedView>>()

        every {
            repository.findOutdatedAssets(
                workgroupId = "2",
                searchTerm = null,
                minSeverity = null,
                pageable = pageable
            )
        } returns expectedPage

        // When: Requesting assets
        val result = service.getOutdatedAssets(authentication, pageable = pageable)

        // Then: Single workgroup ID passed
        assertEquals(expectedPage, result)
        verify(exactly = 1) {
            repository.findOutdatedAssets(
                workgroupId = "2",
                searchTerm = null,
                minSeverity = null,
                pageable = pageable
            )
        }
    }

    @Test
    fun `VULN user with no workgroups gets empty workgroup filter`() {
        // Given: VULN user with no assigned workgroups
        val authentication = mockk<Authentication>()
        every { authentication.roles } returns setOf("VULN")
        every { authentication.attributes } returns mapOf<String, Any>()

        val pageable = Pageable.from(0, 20)
        val expectedPage = mockk<Page<OutdatedAssetMaterializedView>>()

        every {
            repository.findOutdatedAssets(
                workgroupId = null,
                searchTerm = null,
                minSeverity = null,
                pageable = pageable
            )
        } returns expectedPage

        // When: Requesting assets
        val result = service.getOutdatedAssets(authentication, pageable = pageable)

        // Then: Null workgroup filter (should match assets without workgroups)
        assertEquals(expectedPage, result)
        verify(exactly = 1) {
            repository.findOutdatedAssets(
                workgroupId = null,
                searchTerm = null,
                minSeverity = null,
                pageable = pageable
            )
        }
    }

    @Test
    fun `search term is passed through to repository`() {
        // Given: User with search term
        val authentication = mockk<Authentication>()
        every { authentication.roles } returns setOf("ADMIN")
        every { authentication.attributes } returns mapOf<String, Any>()

        val pageable = Pageable.from(0, 20)
        val searchTerm = "production"
        val expectedPage = mockk<Page<OutdatedAssetMaterializedView>>()

        every {
            repository.findOutdatedAssets(
                workgroupId = null,
                searchTerm = searchTerm,
                minSeverity = null,
                pageable = pageable
            )
        } returns expectedPage

        // When: Searching for assets
        val result = service.getOutdatedAssets(
            authentication,
            searchTerm = searchTerm,
            pageable = pageable
        )

        // Then: Search term passed to repository
        assertEquals(expectedPage, result)
        verify(exactly = 1) {
            repository.findOutdatedAssets(
                workgroupId = null,
                searchTerm = searchTerm,
                minSeverity = null,
                pageable = pageable
            )
        }
    }

    @Test
    fun `minimum severity filter is passed through to repository`() {
        // Given: User with severity filter
        val authentication = mockk<Authentication>()
        every { authentication.roles } returns setOf("ADMIN")
        every { authentication.attributes } returns mapOf<String, Any>()

        val pageable = Pageable.from(0, 20)
        val minSeverity = "HIGH"
        val expectedPage = mockk<Page<OutdatedAssetMaterializedView>>()

        every {
            repository.findOutdatedAssets(
                workgroupId = null,
                searchTerm = null,
                minSeverity = minSeverity,
                pageable = pageable
            )
        } returns expectedPage

        // When: Filtering by severity
        val result = service.getOutdatedAssets(
            authentication,
            minSeverity = minSeverity,
            pageable = pageable
        )

        // Then: Severity filter passed to repository
        assertEquals(expectedPage, result)
        verify(exactly = 1) {
            repository.findOutdatedAssets(
                workgroupId = null,
                searchTerm = null,
                minSeverity = minSeverity,
                pageable = pageable
            )
        }
    }

    @Test
    fun `pagination parameters are passed through correctly`() {
        // Given: User with specific pagination
        val authentication = mockk<Authentication>()
        every { authentication.roles } returns setOf("ADMIN")
        every { authentication.attributes } returns mapOf<String, Any>()

        val pageable = Pageable.from(2, 50)  // Page 2, size 50
        val expectedPage = mockk<Page<OutdatedAssetMaterializedView>>()

        every {
            repository.findOutdatedAssets(
                workgroupId = null,
                searchTerm = null,
                minSeverity = null,
                pageable = pageable
            )
        } returns expectedPage

        // When: Requesting specific page
        val result = service.getOutdatedAssets(authentication, pageable = pageable)

        // Then: Pageable passed through
        assertEquals(expectedPage, result)
        verify(exactly = 1) {
            repository.findOutdatedAssets(
                workgroupId = null,
                searchTerm = null,
                minSeverity = null,
                pageable = pageable
            )
        }
    }

    @Test
    fun `getLastRefreshTimestamp returns repository result`() {
        // Given: Repository has timestamp
        val expectedTimestamp = LocalDateTime.now().minusMinutes(5)
        every { repository.findLatestCalculatedAt() } returns expectedTimestamp

        // When: Getting last refresh timestamp
        val result = service.getLastRefreshTimestamp()

        // Then: Returns repository value
        assertEquals(expectedTimestamp, result)
        verify(exactly = 1) { repository.findLatestCalculatedAt() }
    }

    @Test
    fun `getLastRefreshTimestamp returns null when no data`() {
        // Given: Repository has no data
        every { repository.findLatestCalculatedAt() } returns null

        // When: Getting last refresh timestamp
        val result = service.getLastRefreshTimestamp()

        // Then: Returns null
        assertNull(result)
        verify(exactly = 1) { repository.findLatestCalculatedAt() }
    }

    @Test
    fun `countOutdatedAssets for ADMIN returns total count`() {
        // Given: ADMIN user
        val authentication = mockk<Authentication>()
        every { authentication.roles } returns setOf("ADMIN")
        every { authentication.attributes } returns mapOf<String, Any>()
        every { repository.count() } returns 42L

        // When: Counting assets
        val result = service.countOutdatedAssets(authentication)

        // Then: Returns total count
        assertEquals(42L, result)
        verify(exactly = 1) { repository.count() }
        verify(exactly = 0) { repository.countOutdatedAssets(any()) }
    }

    @Test
    fun `countOutdatedAssets for VULN user applies workgroup filter`() {
        // Given: VULN user with workgroups 1, 2, 3
        val authentication = mockk<Authentication>()
        every { authentication.roles } returns setOf("VULN")
        every { authentication.attributes } returns mapOf(
            "workgroupIds" to listOf(1L, 2L, 3L)
        )
        every { repository.countOutdatedAssets("1,2,3") } returns 15L

        // When: Counting assets
        val result = service.countOutdatedAssets(authentication)

        // Then: Returns filtered count
        assertEquals(15L, result)
        verify(exactly = 1) { repository.countOutdatedAssets("1,2,3") }
        verify(exactly = 0) { repository.count() }
    }

    @Test
    fun `ADMIN role takes precedence over VULN role`() {
        // Given: User with both ADMIN and VULN roles
        val authentication = mockk<Authentication>()
        every { authentication.roles } returns setOf("ADMIN", "VULN")
        every { authentication.attributes } returns mapOf(
            "workgroupIds" to listOf(1L, 2L)
        )

        val pageable = Pageable.from(0, 20)
        val expectedPage = mockk<Page<OutdatedAssetMaterializedView>>()

        every {
            repository.findOutdatedAssets(
                workgroupId = null,  // ADMIN should see all
                searchTerm = null,
                minSeverity = null,
                pageable = pageable
            )
        } returns expectedPage

        // When: Requesting assets
        val result = service.getOutdatedAssets(authentication, pageable = pageable)

        // Then: ADMIN behavior (no filtering)
        assertEquals(expectedPage, result)
        verify(exactly = 1) {
            repository.findOutdatedAssets(
                workgroupId = null,  // ADMIN sees all
                searchTerm = null,
                minSeverity = null,
                pageable = pageable
            )
        }
    }
}
