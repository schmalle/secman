package com.secman.contract

import com.secman.domain.OutdatedAssetMaterializedView
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Contract test for OutdatedAssetMaterializedView entity schema
 *
 * Verifies:
 * - Entity can be persisted and retrieved
 * - All fields are correctly mapped
 * - Indexes are created (verified by successful queries)
 * - Data integrity constraints work
 *
 * Feature: 034-outdated-assets
 * Task: T009
 * Spec reference: data-model.md
 */
@MicronautTest(transactional = true)
class OutdatedAssetMaterializedViewContractTest {

    @Inject
    lateinit var repository: OutdatedAssetMaterializedViewRepository

    @Test
    fun `should persist and retrieve OutdatedAssetMaterializedView entity`() {
        // Given: A materialized view record
        val entity = OutdatedAssetMaterializedView(
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
        )

        // When: Entity is saved
        val saved = repository.save(entity)

        // Then: Entity can be retrieved with all fields intact
        assertNotNull(saved.id)
        val retrieved = repository.findById(saved.id!!).orElseThrow()

        assertEquals(1234L, retrieved.assetId)
        assertEquals("server-prod-01.example.com", retrieved.assetName)
        assertEquals("SERVER", retrieved.assetType)
        assertEquals(18, retrieved.totalOverdueCount)
        assertEquals(5, retrieved.criticalCount)
        assertEquals(10, retrieved.highCount)
        assertEquals(3, retrieved.mediumCount)
        assertEquals(0, retrieved.lowCount)
        assertEquals(180, retrieved.oldestVulnDays)
        assertEquals("CVE-2023-1234", retrieved.oldestVulnId)
        assertEquals("1,3,5", retrieved.workgroupIds)
        assertNotNull(retrieved.lastCalculatedAt)
    }

    @Test
    fun `should handle null optional fields`() {
        // Given: Entity with null optional fields
        val entity = OutdatedAssetMaterializedView(
            assetId = 5678L,
            assetName = "server-test-02.example.com",
            assetType = "SERVER",
            totalOverdueCount = 5,
            criticalCount = 2,
            highCount = 3,
            mediumCount = 0,
            lowCount = 0,
            oldestVulnDays = 45,
            oldestVulnId = null,  // Optional
            workgroupIds = null,  // Optional
            lastCalculatedAt = LocalDateTime.now()
        )

        // When: Entity is saved
        val saved = repository.save(entity)

        // Then: Entity can be retrieved with null fields
        val retrieved = repository.findById(saved.id!!).orElseThrow()
        assertNull(retrieved.oldestVulnId)
        assertNull(retrieved.workgroupIds)
    }

    @Test
    fun `should enforce default values for severity counts`() {
        // Given: Entity created without explicit severity counts
        val entity = OutdatedAssetMaterializedView(
            assetId = 9999L,
            assetName = "server-minimal.example.com",
            assetType = "SERVER",
            totalOverdueCount = 1,
            oldestVulnDays = 50,
            lastCalculatedAt = LocalDateTime.now()
        )

        // When: Entity is saved
        val saved = repository.save(entity)

        // Then: Default values are applied
        val retrieved = repository.findById(saved.id!!).orElseThrow()
        assertEquals(0, retrieved.criticalCount)
        assertEquals(0, retrieved.highCount)
        assertEquals(0, retrieved.mediumCount)
        assertEquals(0, retrieved.lowCount)
    }

    @Test
    fun `should support deleteAll for refresh operation`() {
        // Given: Multiple entities in database
        val entity1 = OutdatedAssetMaterializedView(
            assetId = 1L,
            assetName = "asset-1",
            assetType = "SERVER",
            totalOverdueCount = 5,
            oldestVulnDays = 100,
            lastCalculatedAt = LocalDateTime.now()
        )
        val entity2 = OutdatedAssetMaterializedView(
            assetId = 2L,
            assetName = "asset-2",
            assetType = "SERVER",
            totalOverdueCount = 3,
            oldestVulnDays = 60,
            lastCalculatedAt = LocalDateTime.now()
        )

        repository.save(entity1)
        repository.save(entity2)
        assertEquals(2, repository.count())

        // When: deleteAll is called
        repository.deleteAll()

        // Then: All entities are deleted
        assertEquals(0, repository.count())
    }

    @Test
    fun `should query last refresh timestamp`() {
        // Given: Multiple entities with different timestamps
        val now = LocalDateTime.now()
        val earlier = now.minusHours(2)

        val entity1 = OutdatedAssetMaterializedView(
            assetId = 1L,
            assetName = "asset-1",
            assetType = "SERVER",
            totalOverdueCount = 5,
            oldestVulnDays = 100,
            lastCalculatedAt = earlier
        )
        val entity2 = OutdatedAssetMaterializedView(
            assetId = 2L,
            assetName = "asset-2",
            assetType = "SERVER",
            totalOverdueCount = 3,
            oldestVulnDays = 60,
            lastCalculatedAt = now
        )

        repository.save(entity1)
        repository.save(entity2)

        // When: Querying for last refresh timestamp
        val lastRefresh = repository.getLastRefreshTimestamp()

        // Then: Returns the most recent timestamp
        assertTrue(lastRefresh.isPresent)
        assertTrue(lastRefresh.get().isAfter(earlier) || lastRefresh.get().isEqual(now))
    }
}
