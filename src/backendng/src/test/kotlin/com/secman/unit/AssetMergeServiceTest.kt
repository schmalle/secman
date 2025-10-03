package com.secman.unit

import com.secman.domain.Asset
import com.secman.repository.AssetRepository
import com.secman.service.AssetMergeService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.*

/**
 * Unit test for AssetMergeService
 *
 * Tests asset merging logic in isolation using mocks:
 * - Group append and deduplication (comma-separated)
 * - IP update logic
 * - Field preservation (owner, type, description)
 * - Auto-creation with defaults
 *
 * Related to: Feature 003-i-want-to (Vulnerability Management System)
 */
class AssetMergeServiceTest {

    private lateinit var assetRepository: AssetRepository
    private lateinit var service: AssetMergeService

    @BeforeEach
    fun setup() {
        assetRepository = mockk()
        service = AssetMergeService(assetRepository)
    }

    @Test
    @DisplayName("mergeGroups should append and deduplicate groups")
    fun testMergeGroupsAppendAndDeduplicate() {
        // Test merging overlapping groups
        val existing = "Production, Web Servers"
        val new = "Web Servers, Database Servers"

        val result = service.mergeGroups(existing, new)

        // Should have all 3 unique groups, alphabetically sorted
        assertNotNull(result)
        val groups = result!!.split(",").map { it.trim() }
        assertEquals(3, groups.size)
        assertTrue(groups.contains("Database Servers"))
        assertTrue(groups.contains("Production"))
        assertTrue(groups.contains("Web Servers"))

        // Verify sorted
        assertEquals(listOf("Database Servers", "Production", "Web Servers"), groups)
    }

    @Test
    @DisplayName("mergeGroups should handle null existing groups")
    fun testMergeGroupsNullExisting() {
        val existing: String? = null
        val new = "Group A, Group B"

        val result = service.mergeGroups(existing, new)

        assertNotNull(result)
        assertEquals("Group A, Group B", result)
    }

    @Test
    @DisplayName("mergeGroups should handle null new groups")
    fun testMergeGroupsNullNew() {
        val existing = "Group A, Group B"
        val new: String? = null

        val result = service.mergeGroups(existing, new)

        assertNotNull(result)
        assertEquals("Group A, Group B", result)
    }

    @Test
    @DisplayName("mergeGroups should handle both null")
    fun testMergeGroupsBothNull() {
        val existing: String? = null
        val new: String? = null

        val result = service.mergeGroups(existing, new)

        assertNull(result)
    }

    @Test
    @DisplayName("mergeGroups should trim whitespace and filter empty strings")
    fun testMergeGroupsWhitespaceTrimming() {
        val existing = " Group A ,  Group B  "
        val new = "  Group C  ,   "

        val result = service.mergeGroups(existing, new)

        assertNotNull(result)
        val groups = result!!.split(",").map { it.trim() }
        assertEquals(3, groups.size)
        assertTrue(groups.contains("Group A"))
        assertTrue(groups.contains("Group B"))
        assertTrue(groups.contains("Group C"))
    }

    @Test
    @DisplayName("mergeGroups should deduplicate case-sensitive duplicates")
    fun testMergeGroupsCaseSensitive() {
        val existing = "Production, production"
        val new = "PRODUCTION"

        val result = service.mergeGroups(existing, new)

        // All three variations should be preserved (case-sensitive deduplication)
        assertNotNull(result)
        val groups = result!!.split(",").map { it.trim() }
        assertEquals(3, groups.size)
    }

    @Test
    @DisplayName("findOrCreateAsset should create new asset with defaults when not found")
    fun testFindOrCreateAssetCreatesNewWithDefaults() {
        val hostname = "new-server.example.com"

        // Mock: asset not found
        every { assetRepository.findByName(hostname) } returns Optional.empty()

        // Mock: save returns asset with ID
        every { assetRepository.save(any()) } answers {
            val asset = firstArg<Asset>()
            asset.id = 1L
            asset
        }

        // Execute
        val result = service.findOrCreateAsset(
            hostname = hostname,
            ip = "10.0.0.1",
            groups = "Production"
        )

        // Verify defaults were set
        assertEquals(hostname, result.name)
        assertEquals(AssetMergeService.DEFAULT_OWNER, result.owner)
        assertEquals(AssetMergeService.DEFAULT_TYPE, result.type)
        assertEquals(AssetMergeService.DEFAULT_DESCRIPTION, result.description)
        assertEquals("10.0.0.1", result.ip)
        assertEquals("Production", result.groups)

        // Verify save was called
        verify(exactly = 1) { assetRepository.save(any()) }
    }

    @Test
    @DisplayName("findOrCreateAsset should merge data when asset exists")
    fun testFindOrCreateAssetMergesWhenExists() {
        val hostname = "existing-server.example.com"

        // Existing asset
        val existingAsset = Asset(
            name = hostname,
            owner = "Dev Team",
            type = "Application",
            description = "Existing server",
            ip = "192.168.1.100",
            groups = "Production"
        )
        existingAsset.id = 1L

        // Mock: asset found
        every { assetRepository.findByName(hostname) } returns Optional.of(existingAsset)

        // Mock: update returns updated asset
        every { assetRepository.update(any()) } answers {
            val asset = firstArg<Asset>()
            asset.updatedAt = LocalDateTime.now()
            asset
        }

        // Execute with new groups
        val result = service.findOrCreateAsset(
            hostname = hostname,
            ip = "192.168.1.100",
            groups = "Web Servers"
        )

        // Verify merge happened
        assertNotNull(result.groups)
        assertTrue(result.groups!!.contains("Production"))
        assertTrue(result.groups!!.contains("Web Servers"))

        // Verify owner/type/description preserved
        assertEquals("Dev Team", result.owner)
        assertEquals("Application", result.type)
        assertEquals("Existing server", result.description)

        // Verify update was called
        verify(exactly = 1) { assetRepository.update(any()) }
        verify(exactly = 0) { assetRepository.save(any()) }
    }

    @Test
    @DisplayName("findOrCreateAsset should update IP when different")
    fun testFindOrCreateAssetUpdatesIp() {
        val hostname = "ip-change.example.com"

        // Existing asset with old IP
        val existingAsset = Asset(
            name = hostname,
            owner = "Admin",
            type = "Server",
            ip = "192.168.1.100"
        )
        existingAsset.id = 1L

        // Mock
        every { assetRepository.findByName(hostname) } returns Optional.of(existingAsset)
        every { assetRepository.update(any()) } answers {
            val asset = firstArg<Asset>()
            asset.updatedAt = LocalDateTime.now()
            asset
        }

        // Execute with new IP
        val result = service.findOrCreateAsset(
            hostname = hostname,
            ip = "10.0.0.50"
        )

        // Verify IP was updated
        assertEquals("10.0.0.50", result.ip)

        // Verify update was called
        verify(exactly = 1) { assetRepository.update(any()) }
    }

    @Test
    @DisplayName("findOrCreateAsset should not update when IP is same")
    fun testFindOrCreateAssetNoUpdateWhenIpSame() {
        val hostname = "no-change.example.com"

        // Existing asset
        val existingAsset = Asset(
            name = hostname,
            owner = "Admin",
            type = "Server",
            ip = "192.168.1.100"
        )
        existingAsset.id = 1L

        // Mock
        every { assetRepository.findByName(hostname) } returns Optional.of(existingAsset)

        // Execute with same IP
        val result = service.findOrCreateAsset(
            hostname = hostname,
            ip = "192.168.1.100"
        )

        // Verify no update called (no changes)
        verify(exactly = 0) { assetRepository.update(any()) }
        assertEquals("192.168.1.100", result.ip)
    }

    @Test
    @DisplayName("findOrCreateAsset should update cloud metadata when different")
    fun testFindOrCreateAssetUpdatesCloudMetadata() {
        val hostname = "cloud-server.example.com"

        // Existing asset with no cloud metadata
        val existingAsset = Asset(
            name = hostname,
            owner = "Admin",
            type = "Server"
        )
        existingAsset.id = 1L

        // Mock
        every { assetRepository.findByName(hostname) } returns Optional.of(existingAsset)
        every { assetRepository.update(any()) } answers {
            val asset = firstArg<Asset>()
            asset.updatedAt = LocalDateTime.now()
            asset
        }

        // Execute with cloud metadata
        val result = service.findOrCreateAsset(
            hostname = hostname,
            cloudAccountId = "aws-123",
            cloudInstanceId = "i-abc123",
            osVersion = "Ubuntu 22.04",
            adDomain = "corp.example.com"
        )

        // Verify cloud metadata was set
        assertEquals("aws-123", result.cloudAccountId)
        assertEquals("i-abc123", result.cloudInstanceId)
        assertEquals("Ubuntu 22.04", result.osVersion)
        assertEquals("corp.example.com", result.adDomain)

        // Verify update was called
        verify(exactly = 1) { assetRepository.update(any()) }
    }

    @Test
    @DisplayName("findOrCreateAsset should not update cloud metadata when same")
    fun testFindOrCreateAssetNoUpdateWhenCloudMetadataSame() {
        val hostname = "cloud-no-change.example.com"

        // Existing asset with cloud metadata
        val existingAsset = Asset(
            name = hostname,
            owner = "Admin",
            type = "Server",
            cloudAccountId = "aws-123",
            osVersion = "Ubuntu 22.04"
        )
        existingAsset.id = 1L

        // Mock
        every { assetRepository.findByName(hostname) } returns Optional.of(existingAsset)

        // Execute with same cloud metadata
        val result = service.findOrCreateAsset(
            hostname = hostname,
            cloudAccountId = "aws-123",
            osVersion = "Ubuntu 22.04"
        )

        // Verify no update called (no changes)
        verify(exactly = 0) { assetRepository.update(any()) }
        assertEquals("aws-123", result.cloudAccountId)
        assertEquals("Ubuntu 22.04", result.osVersion)
    }

    @Test
    @DisplayName("findOrCreateAsset should preserve owner, type, description even with new data")
    fun testFindOrCreateAssetPreservesOwnerTypeDescription() {
        val hostname = "preserve-test.example.com"

        // Existing asset
        val existingAsset = Asset(
            name = hostname,
            owner = "Original Owner",
            type = "Original Type",
            description = "Original Description"
        )
        existingAsset.id = 1L

        // Mock
        every { assetRepository.findByName(hostname) } returns Optional.of(existingAsset)
        every { assetRepository.update(any()) } answers { firstArg() }

        // Execute with new IP (triggers merge)
        val result = service.findOrCreateAsset(
            hostname = hostname,
            ip = "10.0.0.1"
        )

        // Verify preserved
        assertEquals("Original Owner", result.owner)
        assertEquals("Original Type", result.type)
        assertEquals("Original Description", result.description)
    }

    @Test
    @DisplayName("findOrCreateAsset should handle all null optional parameters")
    fun testFindOrCreateAssetWithAllNulls() {
        val hostname = "minimal.example.com"

        // Mock: asset not found
        every { assetRepository.findByName(hostname) } returns Optional.empty()

        // Mock: save
        every { assetRepository.save(any()) } answers {
            val asset = firstArg<Asset>()
            asset.id = 1L
            asset
        }

        // Execute with all nulls
        val result = service.findOrCreateAsset(
            hostname = hostname,
            ip = null,
            groups = null,
            cloudAccountId = null,
            cloudInstanceId = null,
            osVersion = null,
            adDomain = null
        )

        // Verify created with defaults and nulls
        assertEquals(hostname, result.name)
        assertEquals(AssetMergeService.DEFAULT_OWNER, result.owner)
        assertEquals(AssetMergeService.DEFAULT_TYPE, result.type)
        assertEquals(AssetMergeService.DEFAULT_DESCRIPTION, result.description)
        assertNull(result.ip)
        assertNull(result.groups)
        assertNull(result.cloudAccountId)
        assertNull(result.cloudInstanceId)
        assertNull(result.osVersion)
        assertNull(result.adDomain)
    }

    @Test
    @DisplayName("mergeGroups should produce sorted, comma-separated output")
    fun testMergeGroupsOutputFormat() {
        val existing = "Zebra, Apple"
        val new = "Banana"

        val result = service.mergeGroups(existing, new)

        // Should be sorted alphabetically
        assertEquals("Apple, Banana, Zebra", result)
    }

    @Test
    @DisplayName("findOrCreateAsset multiple merges should accumulate groups correctly")
    fun testMultipleMergesAccumulateGroups() {
        val hostname = "multi-merge.example.com"

        // Initial asset
        val asset = Asset(
            name = hostname,
            owner = "Admin",
            type = "Server",
            groups = "Group A"
        )
        asset.id = 1L

        // Mock: asset found
        every { assetRepository.findByName(hostname) } returns Optional.of(asset)
        every { assetRepository.update(any()) } answers {
            val updated = firstArg<Asset>()
            asset.groups = updated.groups // Persist the change
            updated
        }

        // First merge
        service.findOrCreateAsset(hostname = hostname, groups = "Group B")
        assertTrue(asset.groups!!.contains("Group A"))
        assertTrue(asset.groups!!.contains("Group B"))

        // Second merge
        service.findOrCreateAsset(hostname = hostname, groups = "Group C")
        assertTrue(asset.groups!!.contains("Group A"))
        assertTrue(asset.groups!!.contains("Group B"))
        assertTrue(asset.groups!!.contains("Group C"))
    }
}
