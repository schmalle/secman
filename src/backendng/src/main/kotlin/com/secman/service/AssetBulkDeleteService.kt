package com.secman.service

import com.secman.dto.BulkDeleteResult
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.ScanResultRepository
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service for bulk asset deletion operations
 * Feature: 029-asset-bulk-operations (User Story 1 - Bulk Delete Assets)
 *
 * Related Requirements:
 * - FR-003: Delete all assets from database when ADMIN confirms
 * - FR-007: Handle cascade deletion of related data (vulnerabilities, scan results)
 * - FR-008: Execute within transaction with rollback on failure
 *
 * Performance Target:
 * - Delete 10K+ assets in <30 seconds (SC-001)
 *
 * Concurrency Control:
 * - Uses AtomicBoolean semaphore for first-request-wins pattern
 * - Second concurrent request returns 409 Conflict
 */
@Singleton
open class AssetBulkDeleteService(
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val scanResultRepository: ScanResultRepository,
    private val entityManager: EntityManager
) {

    private val log = LoggerFactory.getLogger(AssetBulkDeleteService::class.java)

    // Semaphore for concurrent delete protection
    private val deletionInProgress = AtomicBoolean(false)

    /**
     * Custom exception for concurrent bulk delete attempts
     */
    class ConcurrentOperationException(message: String) : RuntimeException(message)

    /**
     * Delete all assets with manual cascade delete
     * FR-003, FR-007, FR-008: Transactional delete with cascade and rollback
     *
     * @return BulkDeleteResult with counts of deleted entities
     * @throws ConcurrentOperationException if another bulk delete is in progress
     */
    @Transactional
    open fun deleteAllAssets(): BulkDeleteResult {
        // Check semaphore - throw exception if another delete is in progress
        if (!deletionInProgress.compareAndSet(false, true)) {
            log.warn("Bulk delete rejected - another operation already in progress")
            throw ConcurrentOperationException("Bulk asset deletion already in progress")
        }

        try {
            log.info("Starting bulk delete of all assets...")

            // Count entities before deletion
            val assetCount = assetRepository.count().toInt()
            val vulnCount = vulnerabilityRepository.count().toInt()
            val scanResultCount = scanResultRepository.count().toInt()

            log.info("Entities to delete: $assetCount assets, $vulnCount vulnerabilities, $scanResultCount scan results")

            // Manual cascade delete in correct order (children first)
            // Step 1: Clear asset-workgroup join table (native SQL)
            val deletedWorkgroupLinks = entityManager
                .createNativeQuery("DELETE FROM asset_workgroups")
                .executeUpdate()
            log.info("Deleted $deletedWorkgroupLinks asset-workgroup links")

            // Step 2: Delete all vulnerabilities
            vulnerabilityRepository.deleteAll()
            log.info("Deleted $vulnCount vulnerabilities")

            // Step 3: Delete all scan results
            scanResultRepository.deleteAll()
            log.info("Deleted $scanResultCount scan results")

            // Step 4: Delete all assets
            assetRepository.deleteAll()
            log.info("Deleted $assetCount assets")

            // Clear EntityManager cache to prevent stale data
            entityManager.clear()
            log.info("EntityManager cache cleared")

            val result = BulkDeleteResult.success(assetCount, vulnCount, scanResultCount)
            log.info("Bulk delete completed successfully: ${result.message}")

            return result

        } finally {
            // Always release semaphore
            deletionInProgress.set(false)
            log.debug("Bulk delete semaphore released")
        }
    }
}
