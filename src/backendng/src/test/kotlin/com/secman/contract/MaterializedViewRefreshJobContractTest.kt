package com.secman.contract

import com.secman.domain.MaterializedViewRefreshJob
import com.secman.domain.RefreshJobStatus
import com.secman.repository.MaterializedViewRefreshJobRepository
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Contract test for MaterializedViewRefreshJob entity schema
 *
 * Verifies:
 * - Entity can be persisted and retrieved
 * - Status enum transitions work correctly
 * - Helper methods (updateProgress, markCompleted, markFailed) function properly
 * - Indexes support concurrent refresh prevention
 *
 * Feature: 034-outdated-assets
 * Task: T010
 * Spec reference: data-model.md
 */
@MicronautTest(transactional = true)
class MaterializedViewRefreshJobContractTest {

    @Inject
    lateinit var repository: MaterializedViewRefreshJobRepository

    @Test
    fun `should persist and retrieve MaterializedViewRefreshJob entity`() {
        // Given: A refresh job
        val job = MaterializedViewRefreshJob(
            status = RefreshJobStatus.RUNNING,
            triggeredBy = "Manual Refresh",
            startedAt = LocalDateTime.now(),
            assetsProcessed = 0,
            totalAssets = 10000,
            progressPercentage = 0
        )

        // When: Job is saved
        val saved = repository.save(job)

        // Then: Job can be retrieved with all fields intact
        assertNotNull(saved.id)
        val retrieved = repository.findById(saved.id!!).orElseThrow()

        assertEquals(RefreshJobStatus.RUNNING, retrieved.status)
        assertEquals("Manual Refresh", retrieved.triggeredBy)
        assertNotNull(retrieved.startedAt)
        assertNull(retrieved.completedAt)
        assertEquals(0, retrieved.assetsProcessed)
        assertEquals(10000, retrieved.totalAssets)
        assertEquals(0, retrieved.progressPercentage)
        assertNull(retrieved.errorMessage)
        assertNull(retrieved.durationMs)
    }

    @Test
    fun `should update progress and calculate percentage`() {
        // Given: A running job
        val job = MaterializedViewRefreshJob(
            triggeredBy = "CLI Import",
            totalAssets = 10000
        )
        val saved = repository.save(job)

        // When: Progress is updated
        saved.updateProgress(5000)
        val updated = repository.update(saved)

        // Then: Progress and percentage are updated
        assertEquals(5000, updated.assetsProcessed)
        assertEquals(50, updated.progressPercentage)

        // When: Progress reaches 100%
        updated.updateProgress(10000)
        repository.update(updated)

        // Then: Progress is 100%
        assertEquals(10000, updated.assetsProcessed)
        assertEquals(100, updated.progressPercentage)
    }

    @Test
    fun `should handle zero total assets in progress calculation`() {
        // Given: Job with zero total assets (edge case)
        val job = MaterializedViewRefreshJob(
            triggeredBy = "Test",
            totalAssets = 0
        )
        val saved = repository.save(job)

        // When: Progress is updated
        saved.updateProgress(0)

        // Then: No division by zero error, percentage remains 0
        assertEquals(0, saved.progressPercentage)
    }

    @Test
    fun `should mark job as completed with duration calculation`() {
        // Given: A running job
        val job = MaterializedViewRefreshJob(
            triggeredBy = "Manual Refresh",
            totalAssets = 1000
        )
        val saved = repository.save(job)

        // Simulate some work time
        Thread.sleep(100)

        // When: Job is marked as completed
        saved.markCompleted()
        val completed = repository.update(saved)

        // Then: Status and fields are updated
        assertEquals(RefreshJobStatus.COMPLETED, completed.status)
        assertNotNull(completed.completedAt)
        assertNotNull(completed.durationMs)
        assertTrue(completed.durationMs!! >= 100) // At least 100ms
        assertEquals(100, completed.progressPercentage)
    }

    @Test
    fun `should mark job as failed with error message`() {
        // Given: A running job
        val job = MaterializedViewRefreshJob(
            triggeredBy = "CLI Import",
            totalAssets = 10000
        )
        val saved = repository.save(job)

        // When: Job fails with error
        val errorMessage = "Database connection timeout after 120 seconds"
        saved.markFailed(errorMessage)
        val failed = repository.update(saved)

        // Then: Status and error are recorded
        assertEquals(RefreshJobStatus.FAILED, failed.status)
        assertEquals(errorMessage, failed.errorMessage)
        assertNotNull(failed.completedAt)
        assertNotNull(failed.durationMs)
    }

    @Test
    fun `should truncate long error messages to 1000 characters`() {
        // Given: A running job
        val job = MaterializedViewRefreshJob(
            triggeredBy = "Test",
            totalAssets = 100
        )
        val saved = repository.save(job)

        // When: Job fails with very long error message
        val longError = "ERROR: ".repeat(300) // 1800 characters
        saved.markFailed(longError)
        val failed = repository.update(saved)

        // Then: Error message is truncated to 1000 characters
        assertNotNull(failed.errorMessage)
        assertEquals(1000, failed.errorMessage!!.length)
    }

    @Test
    fun `should find running job for concurrent prevention`() {
        // Given: Multiple jobs with different statuses
        val runningJob = MaterializedViewRefreshJob(
            status = RefreshJobStatus.RUNNING,
            triggeredBy = "Manual Refresh",
            totalAssets = 10000
        )
        val completedJob = MaterializedViewRefreshJob(
            status = RefreshJobStatus.COMPLETED,
            triggeredBy = "CLI Import",
            totalAssets = 5000
        )

        repository.save(completedJob)
        Thread.sleep(10) // Ensure different timestamps
        repository.save(runningJob)

        // When: Querying for running job
        val found = repository.findRunningJob()

        // Then: Returns the running job
        assertTrue(found.isPresent)
        assertEquals(RefreshJobStatus.RUNNING, found.get().status)
        assertEquals("Manual Refresh", found.get().triggeredBy)
    }

    @Test
    fun `should return empty when no running job exists`() {
        // Given: Only completed jobs
        val completedJob = MaterializedViewRefreshJob(
            status = RefreshJobStatus.COMPLETED,
            triggeredBy = "CLI Import",
            totalAssets = 5000
        )
        repository.save(completedJob)

        // When: Querying for running job
        val found = repository.findRunningJob()

        // Then: Returns empty
        assertFalse(found.isPresent)
    }

    @Test
    fun `should count jobs by status`() {
        // Given: Jobs with different statuses
        repository.save(MaterializedViewRefreshJob(
            status = RefreshJobStatus.RUNNING,
            triggeredBy = "Test 1",
            totalAssets = 100
        ))
        repository.save(MaterializedViewRefreshJob(
            status = RefreshJobStatus.COMPLETED,
            triggeredBy = "Test 2",
            totalAssets = 100
        ))
        repository.save(MaterializedViewRefreshJob(
            status = RefreshJobStatus.COMPLETED,
            triggeredBy = "Test 3",
            totalAssets = 100
        ))
        repository.save(MaterializedViewRefreshJob(
            status = RefreshJobStatus.FAILED,
            triggeredBy = "Test 4",
            totalAssets = 100
        ))

        // When: Counting by status
        val runningCount = repository.countByStatus(RefreshJobStatus.RUNNING)
        val completedCount = repository.countByStatus(RefreshJobStatus.COMPLETED)
        val failedCount = repository.countByStatus(RefreshJobStatus.FAILED)

        // Then: Counts are accurate
        assertEquals(1, runningCount)
        assertEquals(2, completedCount)
        assertEquals(1, failedCount)
    }
}
