package com.secman.service

import com.secman.domain.*
import com.secman.repository.AssetRepository
import com.secman.repository.MaterializedViewRefreshJobRepository
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.context.event.ApplicationEventPublisher
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Unit tests for MaterializedViewRefreshService
 *
 * Tests:
 * - Async refresh trigger creates job
 * - Refresh execution with progress tracking
 * - Batch processing logic
 * - Error handling
 * - Progress event publishing
 *
 * Feature: 034-outdated-assets
 * Task: T012
 * Spec reference: data-model.md, research.md
 */
class MaterializedViewRefreshServiceTest {

    private lateinit var service: MaterializedViewRefreshService
    private lateinit var refreshJobRepository: MaterializedViewRefreshJobRepository
    private lateinit var outdatedAssetRepository: OutdatedAssetMaterializedViewRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var vulnerabilityRepository: VulnerabilityRepository
    private lateinit var vulnerabilityConfigService: VulnerabilityConfigService
    private lateinit var eventPublisher: ApplicationEventPublisher<RefreshProgressEvent>
    private lateinit var vulnerabilityExceptionService: VulnerabilityExceptionService

    @BeforeEach
    fun setup() {
        refreshJobRepository = mockk()
        outdatedAssetRepository = mockk()
        assetRepository = mockk()
        vulnerabilityRepository = mockk()
        vulnerabilityConfigService = mockk()
        eventPublisher = mockk()
        vulnerabilityExceptionService = mockk()

        service = MaterializedViewRefreshService(
            refreshJobRepository,
            outdatedAssetRepository,
            assetRepository,
            vulnerabilityRepository,
            vulnerabilityConfigService,
            eventPublisher,
            vulnerabilityExceptionService
        )
    }

    @Test
    fun `triggerAsyncRefresh should create job and return immediately`() {
        // Given
        val triggeredBy = "Manual Refresh"
        val job = MaterializedViewRefreshJob(
            id = 42L,
            triggeredBy = triggeredBy,
            totalAssets = 0
        )

        every { refreshJobRepository.save(any()) } returns job

        // When
        val result = service.triggerAsyncRefresh(triggeredBy)

        // Then
        assertEquals(42L, result.id)
        assertEquals(triggeredBy, result.triggeredBy)
        verify(exactly = 1) { refreshJobRepository.save(any()) }
    }

    @Test
    fun `executeRefresh should clear old data and create new materialized records`() {
        // Given: Threshold and mock data
        val threshold = 30
        val job = MaterializedViewRefreshJob(
            id = 1L,
            triggeredBy = "Test",
            totalAssets = 0
        )

        val asset = Asset(
            id = 100L,
            name = "server-prod-01",
            type = "SERVER",
            owner = "IT Team"
        )

        val oldVuln = Vulnerability(
            id = 1L,
            asset = asset,
            vulnerabilityId = "CVE-2023-1234",
            cvssSeverity = "CRITICAL",
            scanTimestamp = LocalDateTime.now().minusDays(90),
            daysOpen = "90 days"
        )

        every { vulnerabilityConfigService.getReminderOneDays() } returns threshold
        every { outdatedAssetRepository.deleteAll() } just Runs
        every { assetRepository.findAll() } returns listOf(asset)
        every { vulnerabilityRepository.findByAssetId(100L, any()) } returns io.micronaut.data.model.Page.of(listOf(oldVuln), io.micronaut.data.model.Pageable.UNPAGED, 1)
        every { vulnerabilityExceptionService.isVulnerabilityExcepted(any(), any()) } returns false
        every { outdatedAssetRepository.saveAll(any<List<OutdatedAssetMaterializedView>>()) } returns listOf()
        every { refreshJobRepository.update(any()) } returns job
        every { eventPublisher.publishEvent(any()) } just Runs

        // When
        service.executeRefresh(job)

        // Then: Old data deleted
        verify(exactly = 1) { outdatedAssetRepository.deleteAll() }

        // Then: New records created
        verify(exactly = 1) { outdatedAssetRepository.saveAll(any<List<OutdatedAssetMaterializedView>>()) }

        // Then: Job marked as completed
        assertEquals(RefreshJobStatus.COMPLETED, job.status)
        assertEquals(100, job.progressPercentage)
    }

    @Test
    fun `executeRefresh should handle empty result when no outdated assets`() {
        // Given: No assets have overdue vulnerabilities
        val threshold = 30
        val job = MaterializedViewRefreshJob(
            id = 1L,
            triggeredBy = "Test",
            totalAssets = 0
        )

        every { vulnerabilityConfigService.getReminderOneDays() } returns threshold
        every { outdatedAssetRepository.deleteAll() } just Runs
        every { assetRepository.findAll() } returns emptyList()
        every { refreshJobRepository.update(any()) } returns job
        every { eventPublisher.publishEvent(any()) } just Runs

        // When
        service.executeRefresh(job)

        // Then: Job completed with 0 assets
        assertEquals(0, job.totalAssets)
        assertEquals(RefreshJobStatus.COMPLETED, job.status)
        verify(exactly = 0) { outdatedAssetRepository.saveAll(any<List<OutdatedAssetMaterializedView>>()) }
    }

    @Test
    fun `executeRefresh should filter assets to only those with overdue vulnerabilities`() {
        // Given: Mix of assets with and without overdue vulns
        val threshold = 30
        val job = MaterializedViewRefreshJob(
            id = 1L,
            triggeredBy = "Test",
            totalAssets = 0
        )

        val assetWithOverdue = Asset(
            id = 1L,
            name = "asset-overdue",
            type = "SERVER",
            owner = "Team"
        )
        val assetWithoutOverdue = Asset(
            id = 2L,
            name = "asset-ok",
            type = "SERVER",
            owner = "Team"
        )

        val overdueVuln = Vulnerability(
            id = 1L,
            asset = assetWithOverdue,
            vulnerabilityId = "CVE-2023-1",
            cvssSeverity = "HIGH",
            scanTimestamp = LocalDateTime.now().minusDays(90),
            daysOpen = "90 days"
        )
        val recentVuln = Vulnerability(
            id = 2L,
            asset = assetWithoutOverdue,
            vulnerabilityId = "CVE-2024-1",
            cvssSeverity = "MEDIUM",
            scanTimestamp = LocalDateTime.now().minusDays(10),
            daysOpen = "10 days"
        )

        every { vulnerabilityConfigService.getReminderOneDays() } returns threshold
        every { outdatedAssetRepository.deleteAll() } just Runs
        every { assetRepository.findAll() } returns listOf(assetWithOverdue, assetWithoutOverdue)
        every { vulnerabilityRepository.findByAssetId(1L, any()) } returns io.micronaut.data.model.Page.of(listOf(overdueVuln), io.micronaut.data.model.Pageable.UNPAGED, 1)
        every { vulnerabilityRepository.findByAssetId(2L, any()) } returns io.micronaut.data.model.Page.of(listOf(recentVuln), io.micronaut.data.model.Pageable.UNPAGED, 1)
        every { vulnerabilityExceptionService.isVulnerabilityExcepted(any(), any()) } returns false
        every { outdatedAssetRepository.saveAll(any<List<OutdatedAssetMaterializedView>>()) } returns listOf()
        every { refreshJobRepository.update(any()) } returns job
        every { eventPublisher.publishEvent(any()) } just Runs

        // When
        service.executeRefresh(job)

        // Then: Only 1 asset should be processed (the overdue one)
        assertEquals(1, job.totalAssets)

        // Verify saveAll was called with 1 record
        verify(exactly = 1) {
            outdatedAssetRepository.saveAll(
                match { list -> list.size == 1 && list[0].assetName == "asset-overdue" }
            )
        }
    }

    @Test
    fun `executeRefresh should calculate severity counts correctly`() {
        // Given: Asset with multiple vulnerabilities of different severities
        val threshold = 30
        val job = MaterializedViewRefreshJob(
            id = 1L,
            triggeredBy = "Test",
            totalAssets = 0
        )

        val asset = Asset(
            id = 1L,
            name = "asset-mixed",
            type = "SERVER",
            owner = "Team"
        )

        val criticalVuln = Vulnerability(
            id = 1L,
            asset = asset,
            vulnerabilityId = "CVE-2023-1",
            cvssSeverity = "CRITICAL",
            scanTimestamp = LocalDateTime.now().minusDays(60),
            daysOpen = "60 days"
        )
        val highVuln1 = Vulnerability(
            id = 2L,
            asset = asset,
            vulnerabilityId = "CVE-2023-2",
            cvssSeverity = "HIGH",
            scanTimestamp = LocalDateTime.now().minusDays(50),
            daysOpen = "50 days"
        )
        val highVuln2 = Vulnerability(
            id = 3L,
            asset = asset,
            vulnerabilityId = "CVE-2023-3",
            cvssSeverity = "HIGH",
            scanTimestamp = LocalDateTime.now().minusDays(45),
            daysOpen = "45 days"
        )
        val mediumVuln = Vulnerability(
            id = 4L,
            asset = asset,
            vulnerabilityId = "CVE-2023-4",
            cvssSeverity = "MEDIUM",
            scanTimestamp = LocalDateTime.now().minusDays(40),
            daysOpen = "40 days"
        )

        every { vulnerabilityConfigService.getReminderOneDays() } returns threshold
        every { outdatedAssetRepository.deleteAll() } just Runs
        every { assetRepository.findAll() } returns listOf(asset)
        every { vulnerabilityRepository.findByAssetId(1L, any()) } returns io.micronaut.data.model.Page.of(
            listOf(criticalVuln, highVuln1, highVuln2, mediumVuln),
            io.micronaut.data.model.Pageable.UNPAGED,
            4
        )
        every { vulnerabilityExceptionService.isVulnerabilityExcepted(any(), any()) } returns false
        every { outdatedAssetRepository.saveAll(any<List<OutdatedAssetMaterializedView>>()) } returns listOf()
        every { refreshJobRepository.update(any()) } returns job
        every { eventPublisher.publishEvent(any()) } just Runs

        // When
        service.executeRefresh(job)

        // Then: Severity counts should be correct
        verify(exactly = 1) {
            outdatedAssetRepository.saveAll(
                match { list ->
                    val record = list[0]
                    record.criticalCount == 1 &&
                    record.highCount == 2 &&
                    record.mediumCount == 1 &&
                    record.lowCount == 0 &&
                    record.totalOverdueCount == 4
                }
            )
        }
    }

    @Test
    fun `executeRefresh should publish progress events`() {
        // Given
        val threshold = 30
        val job = MaterializedViewRefreshJob(
            id = 1L,
            triggeredBy = "Test",
            totalAssets = 0
        )

        val asset = Asset(
            id = 1L,
            name = "asset-1",
            type = "SERVER",
            owner = "Team"
        )

        val vuln = Vulnerability(
            id = 1L,
            asset = asset,
            vulnerabilityId = "CVE-2023-1",
            cvssSeverity = "HIGH",
            scanTimestamp = LocalDateTime.now().minusDays(60),
            daysOpen = "60 days"
        )

        every { vulnerabilityConfigService.getReminderOneDays() } returns threshold
        every { outdatedAssetRepository.deleteAll() } just Runs
        every { assetRepository.findAll() } returns listOf(asset)
        every { vulnerabilityRepository.findByAssetId(1L, any()) } returns io.micronaut.data.model.Page.of(listOf(vuln), io.micronaut.data.model.Pageable.UNPAGED, 1)
        every { vulnerabilityExceptionService.isVulnerabilityExcepted(any(), any()) } returns false
        every { outdatedAssetRepository.saveAll(any<List<OutdatedAssetMaterializedView>>()) } returns listOf()
        every { refreshJobRepository.update(any()) } returns job
        every { eventPublisher.publishEvent(any()) } just Runs

        // When
        service.executeRefresh(job)

        // Then: Progress events should be published
        verify(atLeast = 2) { eventPublisher.publishEvent(any()) }
    }
}
