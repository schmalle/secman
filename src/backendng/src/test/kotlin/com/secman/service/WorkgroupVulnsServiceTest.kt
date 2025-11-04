package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.Vulnerability
import com.secman.domain.Workgroup
import com.secman.fixtures.WorkgroupVulnsTestFixtures
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.WorkgroupRepository
import com.secman.repository.CrowdStrikeImportHistoryRepository
import io.micronaut.security.authentication.Authentication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.every
import io.mockk.mockk
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName

/**
 * Unit tests for WorkgroupVulnsService business logic
 *
 * Feature: 022-wg-vulns-handling - Workgroup-Based Vulnerability View
 *
 * Tests the service layer in isolation using MockK for repository mocking.
 * Focuses on business logic without database dependencies.
 *
 * Test Coverage:
 * - Admin user rejection
 * - No workgroup membership handling
 * - Single/multiple workgroup scenarios
 * - Asset in multiple workgroups (deduplication)
 * - Severity count calculation
 * - Workgroup sorting (alphabetical)
 * - Asset sorting (by vuln count desc)
 * - Global totals calculation
 */
@MicronautTest
class WorkgroupVulnsServiceTest {

    @Inject
    lateinit var workgroupRepository: WorkgroupRepository

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    @Inject
    lateinit var importHistoryRepository: CrowdStrikeImportHistoryRepository

    @Inject
    lateinit var entityManager: EntityManager

    lateinit var service: WorkgroupVulnsService

    @BeforeEach
    fun setup() {
        service = WorkgroupVulnsService(
            workgroupRepository = workgroupRepository,
            assetRepository = assetRepository,
            vulnerabilityRepository = vulnerabilityRepository,
            entityManager = entityManager,
            importHistoryRepository = importHistoryRepository
        )
    }

    // ============================================================
    // Access Control Tests
    // ============================================================

    @Test
    @DisplayName("Service throws IllegalStateException for admin users")
    fun testAdminUserRejection() {
        // Arrange: Create admin authentication
        val adminAuth = mockk<Authentication>()
        every { adminAuth.name } returns "adminuser"
        every { adminAuth.attributes } returns mapOf("email" to "admin@example.com")
        every { adminAuth.roles } returns setOf("ADMIN")

        // Act & Assert
        val exception = assertThrows(IllegalStateException::class.java) {
            service.getWorkgroupVulnsSummary(adminAuth)
        }
        
        // Verify error message
        assertTrue(
            exception.message!!.contains("Admin users should use System Vulns view"),
            "Error message should guide admin users to System Vulns view"
        )
    }

    @Test
    @DisplayName("Service throws NoSuchElementException for users without workgroup memberships")
    fun testNoWorkgroupMembershipsThrowsException() {
        // Arrange: Create regular user authentication
        val userAuth = mockk<Authentication>()
        every { userAuth.name } returns "noworkgroupuser"
        every { userAuth.attributes } returns mapOf("email" to WorkgroupVulnsTestFixtures.TestEmails.NO_WORKGROUP_USER)
        every { userAuth.roles } returns setOf("USER")

        // Mock repository to return empty list (no workgroups)
        every { workgroupRepository.findWorkgroupsByUserEmail(any()) } returns emptyList()

        // Act & Assert
        val exception = assertThrows(NoSuchElementException::class.java) {
            service.getWorkgroupVulnsSummary(userAuth)
        }
        
        // Verify error message is user-friendly
        assertTrue(
            exception.message!!.contains("not a member of any workgroups"),
            "Error message should explain user has no workgroup memberships"
        )
    }

    @Test
    @DisplayName("Service throws IllegalStateException when email not found in authentication")
    fun testMissingEmailThrowsException() {
        // Arrange: Create authentication without email attribute
        val authWithoutEmail = mockk<Authentication>()
        every { authWithoutEmail.name } returns "testuser"
        every { authWithoutEmail.attributes } returns emptyMap<String, Any>()
        every { authWithoutEmail.roles } returns setOf("USER")

        // Act & Assert
        val exception = assertThrows(IllegalStateException::class.java) {
            service.getWorkgroupVulnsSummary(authWithoutEmail)
        }
        
        assertTrue(
            exception.message!!.contains("Email not found in authentication context"),
            "Error message should indicate missing email"
        )
    }

    // ============================================================
    // Single Workgroup Tests
    // ============================================================

    @Test
    @DisplayName("Service returns single workgroup with single asset correctly")
    fun testSingleWorkgroupSingleAsset() {
        // Arrange
        val user = WorkgroupVulnsTestFixtures.createTestUser()
        val workgroup = WorkgroupVulnsTestFixtures.createWorkgroup(name = "Test WG")
        workgroup.id = 1L
        
        val asset = WorkgroupVulnsTestFixtures.createAsset(name = "test-asset")
        asset.id = 1L
        asset.workgroups.add(workgroup)
        workgroup.assets.add(asset)
        workgroup.users.add(user)

        val userAuth = mockk<Authentication>()
        every { userAuth.name } returns "testuser"
        every { userAuth.attributes } returns mapOf("email" to user.email)
        every { userAuth.roles } returns setOf("USER")

        // Mock repository responses
        every { workgroupRepository.findWorkgroupsByUserEmail(user.email) } returns listOf(workgroup)
        every { assetRepository.findByWorkgroupIdIn(listOf(1L)) } returns listOf(asset)

        // Mock EntityManager for severity count query
        val query = mockk<jakarta.persistence.Query>()
        every { entityManager.createNativeQuery(any()) } returns query
        every { query.setParameter(any<String>(), any()) } returns query
        every { query.resultList } returns listOf(
            arrayOf(1L, 5, 1, 2, 1, 1, 0) // assetId=1, total=5, critical=1, high=2, medium=1, low=1, unknown=0
        )

        // Act
        val result = service.getWorkgroupVulnsSummary(userAuth)

        // Assert
        assertNotNull(result)
        assertEquals(1, result.workgroupGroups.size, "Should have 1 workgroup")
        assertEquals("Test WG", result.workgroupGroups[0].workgroupName)
        assertEquals(1, result.workgroupGroups[0].totalAssets)
        assertEquals(5, result.workgroupGroups[0].totalVulnerabilities)
        assertEquals(1, result.workgroupGroups[0].totalCritical)
        assertEquals(2, result.workgroupGroups[0].totalHigh)
        assertEquals(1, result.workgroupGroups[0].totalMedium)
    }

    @Test
    @DisplayName("Service handles workgroup with no assets")
    fun testWorkgroupWithNoAssets() {
        // Arrange
        val user = WorkgroupVulnsTestFixtures.createTestUser()
        val emptyWorkgroup = WorkgroupVulnsTestFixtures.createWorkgroup(name = "Empty WG")
        emptyWorkgroup.id = 1L
        emptyWorkgroup.users.add(user)

        val userAuth = mockk<Authentication>()
        every { userAuth.name } returns "testuser"
        every { userAuth.attributes } returns mapOf("email" to user.email)
        every { userAuth.roles } returns setOf("USER")

        // Mock repository responses
        every { workgroupRepository.findWorkgroupsByUserEmail(user.email) } returns listOf(emptyWorkgroup)
        every { assetRepository.findByWorkgroupIdIn(listOf(1L)) } returns emptyList()

        // Mock EntityManager for severity count query (no assets)
        val query = mockk<jakarta.persistence.Query>()
        every { entityManager.createNativeQuery(any()) } returns query
        every { query.setParameter(any<String>(), any()) } returns query
        every { query.resultList } returns emptyList<Array<Any>>()

        // Act
        val result = service.getWorkgroupVulnsSummary(userAuth)

        // Assert
        assertNotNull(result)
        assertEquals(1, result.workgroupGroups.size, "Should still return workgroup even with no assets")
        assertEquals("Empty WG", result.workgroupGroups[0].workgroupName)
        assertEquals(0, result.workgroupGroups[0].totalAssets, "Should have 0 assets")
        assertEquals(0, result.workgroupGroups[0].totalVulnerabilities, "Should have 0 vulnerabilities")
        assertEquals(0, result.totalAssets, "Global total should be 0")
        assertEquals(0, result.totalVulnerabilities, "Global total should be 0")
    }

    // ============================================================
    // Multiple Workgroup Tests
    // ============================================================

    @Test
    @DisplayName("Service returns multiple workgroups sorted alphabetically by name")
    fun testMultipleWorkgroupsSortedByName() {
        // Arrange
        val user = WorkgroupVulnsTestFixtures.createTestUser()
        
        // Create workgroups in non-alphabetical order
        val wgZebra = WorkgroupVulnsTestFixtures.createWorkgroup(name = "Zebra Team")
        wgZebra.id = 1L
        wgZebra.users.add(user)
        
        val wgAlpha = WorkgroupVulnsTestFixtures.createWorkgroup(name = "Alpha Team")
        wgAlpha.id = 2L
        wgAlpha.users.add(user)
        
        val wgBeta = WorkgroupVulnsTestFixtures.createWorkgroup(name = "Beta Team")
        wgBeta.id = 3L
        wgBeta.users.add(user)

        val userAuth = mockk<Authentication>()
        every { userAuth.name } returns "testuser"
        every { userAuth.attributes } returns mapOf("email" to user.email)
        every { userAuth.roles } returns setOf("USER")

        // Mock repository responses - return in non-alphabetical order from DB
        every { workgroupRepository.findWorkgroupsByUserEmail(user.email) } returns listOf(wgZebra, wgAlpha, wgBeta)
        every { assetRepository.findByWorkgroupIdIn(listOf(1L, 2L, 3L)) } returns emptyList()

        // Mock EntityManager
        val query = mockk<jakarta.persistence.Query>()
        every { entityManager.createNativeQuery(any()) } returns query
        every { query.setParameter(any<String>(), any()) } returns query
        every { query.resultList } returns emptyList<Array<Any>>()

        // Act
        val result = service.getWorkgroupVulnsSummary(userAuth)

        // Assert
        assertEquals(3, result.workgroupGroups.size)
        assertEquals("Alpha Team", result.workgroupGroups[0].workgroupName, "Should be sorted alphabetically")
        assertEquals("Beta Team", result.workgroupGroups[1].workgroupName)
        assertEquals("Zebra Team", result.workgroupGroups[2].workgroupName)
    }

    // ============================================================
    // Asset in Multiple Workgroups Tests (Deduplication)
    // ============================================================

    @Test
    @DisplayName("Service handles asset belonging to multiple workgroups with correct deduplication")
    fun testAssetInMultipleWorkgroupsDeduplication() {
        // Arrange
        val user = WorkgroupVulnsTestFixtures.createTestUser()
        
        val wgA = WorkgroupVulnsTestFixtures.createWorkgroup(name = "Workgroup A")
        wgA.id = 1L
        wgA.users.add(user)
        
        val wgB = WorkgroupVulnsTestFixtures.createWorkgroup(name = "Workgroup B")
        wgB.id = 2L
        wgB.users.add(user)

        // Create shared asset
        val sharedAsset = WorkgroupVulnsTestFixtures.createAsset(name = "shared-server")
        sharedAsset.id = 1L
        sharedAsset.workgroups.add(wgA)
        sharedAsset.workgroups.add(wgB)
        wgA.assets.add(sharedAsset)
        wgB.assets.add(sharedAsset)

        val userAuth = mockk<Authentication>()
        every { userAuth.name } returns "testuser"
        every { userAuth.attributes } returns mapOf("email" to user.email)
        every { userAuth.roles } returns setOf("USER")

        // Mock repository responses
        every { workgroupRepository.findWorkgroupsByUserEmail(user.email) } returns listOf(wgA, wgB)
        every { assetRepository.findByWorkgroupIdIn(listOf(1L, 2L)) } returns listOf(sharedAsset)

        // Mock EntityManager for severity count query
        val query = mockk<jakarta.persistence.Query>()
        every { entityManager.createNativeQuery(any()) } returns query
        every { query.setParameter(any<String>(), any()) } returns query
        every { query.resultList } returns listOf(
            arrayOf(1L, 10, 2, 3, 2, 2, 1) // shared asset has 10 vulns
        )

        // Act
        val result = service.getWorkgroupVulnsSummary(userAuth)

        // Assert
        assertEquals(2, result.workgroupGroups.size, "Should have 2 workgroups")
        
        // Both workgroups should show the shared asset
        assertEquals(1, result.workgroupGroups[0].totalAssets, "Workgroup A should show 1 asset")
        assertEquals(1, result.workgroupGroups[1].totalAssets, "Workgroup B should show 1 asset")
        
        // Global totals should deduplicate the asset
        assertEquals(1, result.totalAssets, "Global total should count shared asset only once")
        assertEquals(10, result.totalVulnerabilities, "Global vulnerabilities should be 10 (not doubled)")
        assertEquals(2, result.globalCritical, "Global critical should be 2 (not doubled)")
        assertEquals(3, result.globalHigh, "Global high should be 3 (not doubled)")
        assertEquals(2, result.globalMedium, "Global medium should be 2 (not doubled)")
    }

    // ============================================================
    // Asset Sorting Tests
    // ============================================================

    @Test
    @DisplayName("Service sorts assets by vulnerability count descending within each workgroup")
    fun testAssetsSortedByVulnCountDescending() {
        // Arrange
        val user = WorkgroupVulnsTestFixtures.createTestUser()
        val workgroup = WorkgroupVulnsTestFixtures.createWorkgroup(name = "Test WG")
        workgroup.id = 1L
        workgroup.users.add(user)

        // Create assets with different vulnerability counts
        val asset1 = WorkgroupVulnsTestFixtures.createAsset(name = "server-1")
        asset1.id = 1L
        asset1.workgroups.add(workgroup)
        
        val asset2 = WorkgroupVulnsTestFixtures.createAsset(name = "server-2")
        asset2.id = 2L
        asset2.workgroups.add(workgroup)
        
        val asset3 = WorkgroupVulnsTestFixtures.createAsset(name = "server-3")
        asset3.id = 3L
        asset3.workgroups.add(workgroup)
        
        workgroup.assets.addAll(listOf(asset1, asset2, asset3))

        val userAuth = mockk<Authentication>()
        every { userAuth.name } returns "testuser"
        every { userAuth.attributes } returns mapOf("email" to user.email)
        every { userAuth.roles } returns setOf("USER")

        // Mock repository responses
        every { workgroupRepository.findWorkgroupsByUserEmail(user.email) } returns listOf(workgroup)
        every { assetRepository.findByWorkgroupIdIn(listOf(1L)) } returns listOf(asset1, asset2, asset3)

        // Mock EntityManager - return in non-sorted order
        val query = mockk<jakarta.persistence.Query>()
        every { entityManager.createNativeQuery(any()) } returns query
        every { query.setParameter(any<String>(), any()) } returns query
        every { query.resultList } returns listOf(
            arrayOf(1L, 5, 1, 2, 1, 1, 0),   // asset1: 5 vulns
            arrayOf(2L, 15, 3, 5, 4, 2, 1),  // asset2: 15 vulns (highest)
            arrayOf(3L, 10, 2, 3, 3, 1, 1)   // asset3: 10 vulns
        )

        // Act
        val result = service.getWorkgroupVulnsSummary(userAuth)

        // Assert
        val assets = result.workgroupGroups[0].assets
        assertEquals(3, assets.size)
        assertEquals("server-2", assets[0].name, "Highest vuln count should be first")
        assertEquals(15, assets[0].vulnerabilityCount)
        assertEquals("server-3", assets[1].name)
        assertEquals(10, assets[1].vulnerabilityCount)
        assertEquals("server-1", assets[2].name, "Lowest vuln count should be last")
        assertEquals(5, assets[2].vulnerabilityCount)
    }

    // ============================================================
    // Severity Calculation Tests
    // ============================================================

    @Test
    @DisplayName("Service calculates severity counts correctly using SQL aggregation")
    fun testSeverityCountCalculation() {
        // Arrange
        val user = WorkgroupVulnsTestFixtures.createTestUser()
        val workgroup = WorkgroupVulnsTestFixtures.createWorkgroup(name = "Test WG")
        workgroup.id = 1L
        workgroup.users.add(user)

        val asset = WorkgroupVulnsTestFixtures.createAsset(name = "test-server")
        asset.id = 1L
        asset.workgroups.add(workgroup)
        workgroup.assets.add(asset)

        val userAuth = mockk<Authentication>()
        every { userAuth.name } returns "testuser"
        every { userAuth.attributes } returns mapOf("email" to user.email)
        every { userAuth.roles } returns setOf("USER")

        // Mock repository responses
        every { workgroupRepository.findWorkgroupsByUserEmail(user.email) } returns listOf(workgroup)
        every { assetRepository.findByWorkgroupIdIn(listOf(1L)) } returns listOf(asset)

        // Mock EntityManager with severity breakdown
        val query = mockk<jakarta.persistence.Query>()
        every { entityManager.createNativeQuery(any()) } returns query
        every { query.setParameter(any<String>(), any()) } returns query
        every { query.resultList } returns listOf(
            arrayOf(1L, 8, 2, 3, 1, 1, 1) // total=8, critical=2, high=3, medium=1, low=1, unknown=1
        )

        // Act
        val result = service.getWorkgroupVulnsSummary(userAuth)

        // Assert
        val assetDto = result.workgroupGroups[0].assets[0]
        assertEquals(8, assetDto.vulnerabilityCount)
        assertEquals(2, assetDto.criticalCount)
        assertEquals(3, assetDto.highCount)
        assertEquals(1, assetDto.mediumCount)
        
        // Workgroup level
        assertEquals(8, result.workgroupGroups[0].totalVulnerabilities)
        assertEquals(2, result.workgroupGroups[0].totalCritical)
        assertEquals(3, result.workgroupGroups[0].totalHigh)
        assertEquals(1, result.workgroupGroups[0].totalMedium)
        
        // Global level
        assertEquals(8, result.totalVulnerabilities)
        assertEquals(2, result.globalCritical)
        assertEquals(3, result.globalHigh)
        assertEquals(1, result.globalMedium)
    }

    @Test
    @DisplayName("Service handles SQL error gracefully and returns null severity counts")
    fun testSeverityCalculationError() {
        // Arrange
        val user = WorkgroupVulnsTestFixtures.createTestUser()
        val workgroup = WorkgroupVulnsTestFixtures.createWorkgroup(name = "Test WG")
        workgroup.id = 1L
        workgroup.users.add(user)

        val asset = WorkgroupVulnsTestFixtures.createAsset(name = "test-server")
        asset.id = 1L
        asset.workgroups.add(workgroup)
        workgroup.assets.add(asset)

        val userAuth = mockk<Authentication>()
        every { userAuth.name } returns "testuser"
        every { userAuth.attributes } returns mapOf("email" to user.email)
        every { userAuth.roles } returns setOf("USER")

        // Mock repository responses
        every { workgroupRepository.findWorkgroupsByUserEmail(user.email) } returns listOf(workgroup)
        every { assetRepository.findByWorkgroupIdIn(listOf(1L)) } returns listOf(asset)

        // Mock EntityManager to throw exception
        every { entityManager.createNativeQuery(any()) } throws RuntimeException("Database error")

        // Act
        val result = service.getWorkgroupVulnsSummary(userAuth)

        // Assert - service should handle error gracefully
        assertNotNull(result)
        val assetDto = result.workgroupGroups[0].assets[0]
        assertEquals(0, assetDto.vulnerabilityCount, "Should default to 0 on error")
        assertNull(assetDto.criticalCount, "Severity should be null on error (backward compatible)")
        assertNull(assetDto.highCount)
        assertNull(assetDto.mediumCount)
    }

    // ============================================================
    // Global Totals Tests
    // ============================================================

    @Test
    @DisplayName("Service calculates global totals correctly across multiple workgroups with unique assets")
    fun testGlobalTotalsWithUniqueAssets() {
        // Arrange
        val user = WorkgroupVulnsTestFixtures.createTestUser()
        
        val wg1 = WorkgroupVulnsTestFixtures.createWorkgroup(name = "WG1")
        wg1.id = 1L
        wg1.users.add(user)
        
        val wg2 = WorkgroupVulnsTestFixtures.createWorkgroup(name = "WG2")
        wg2.id = 2L
        wg2.users.add(user)

        // Create unique assets for each workgroup
        val asset1 = WorkgroupVulnsTestFixtures.createAsset(name = "asset-1")
        asset1.id = 1L
        asset1.workgroups.add(wg1)
        wg1.assets.add(asset1)
        
        val asset2 = WorkgroupVulnsTestFixtures.createAsset(name = "asset-2")
        asset2.id = 2L
        asset2.workgroups.add(wg2)
        wg2.assets.add(asset2)

        val userAuth = mockk<Authentication>()
        every { userAuth.name } returns "testuser"
        every { userAuth.attributes } returns mapOf("email" to user.email)
        every { userAuth.roles } returns setOf("USER")

        // Mock repository responses
        every { workgroupRepository.findWorkgroupsByUserEmail(user.email) } returns listOf(wg1, wg2)
        every { assetRepository.findByWorkgroupIdIn(listOf(1L, 2L)) } returns listOf(asset1, asset2)

        // Mock EntityManager
        val query = mockk<jakarta.persistence.Query>()
        every { entityManager.createNativeQuery(any()) } returns query
        every { query.setParameter(any<String>(), any()) } returns query
        every { query.resultList } returns listOf(
            arrayOf(1L, 10, 2, 3, 2, 2, 1),  // asset1: 10 vulns
            arrayOf(2L, 5, 1, 1, 1, 1, 1)    // asset2: 5 vulns
        )

        // Act
        val result = service.getWorkgroupVulnsSummary(userAuth)

        // Assert
        assertEquals(2, result.totalAssets, "Should count both unique assets")
        assertEquals(15, result.totalVulnerabilities, "Should sum: 10 + 5 = 15")
        assertEquals(3, result.globalCritical, "Should sum: 2 + 1 = 3")
        assertEquals(4, result.globalHigh, "Should sum: 3 + 1 = 4")
        assertEquals(3, result.globalMedium, "Should sum: 2 + 1 = 3")
    }
}
