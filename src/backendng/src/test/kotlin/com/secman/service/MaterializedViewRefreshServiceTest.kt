package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.MaterializedViewRefreshJob
import com.secman.domain.Vulnerability
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Regression tests for the atomicity of the outdated-asset materialized view refresh.
 *
 * The view is read live by the user dashboard's "Overdue Patching" card and the
 * Outdated Assets page. The refresh must never leave the view observably empty
 * while overdue data exists: no destructive write may happen before the new
 * snapshot has been fully computed, and a crash mid-refresh must preserve the
 * previous snapshot.
 */
@DisplayName("MaterializedViewRefreshService atomic swap")
class MaterializedViewRefreshServiceTest {

    private val refreshJobRepository = mockk<com.secman.repository.MaterializedViewRefreshJobRepository>(relaxed = true)
    private val outdatedAssetRepository = mockk<com.secman.repository.OutdatedAssetMaterializedViewRepository>(relaxed = true)
    private val vulnerabilityRepository = mockk<com.secman.repository.VulnerabilityRepository>(relaxed = true)
    private val vulnerabilityConfigService = mockk<VulnerabilityConfigService>()
    private val eventPublisher = mockk<io.micronaut.context.event.ApplicationEventPublisher<com.secman.domain.RefreshProgressEvent>>(relaxed = true)
    private val vulnerabilityStatisticsCacheService = mockk<VulnerabilityStatisticsCacheService>(relaxed = true)
    private val assetHeatmapService = mockk<AssetHeatmapService>(relaxed = true)
    private val vulnerabilityService = mockk<VulnerabilityService>(relaxed = true)
    private val awsCleanServerKpiService = mockk<AwsCleanServerKpiService>(relaxed = true)

    private lateinit var service: MaterializedViewRefreshService

    private val job = MaterializedViewRefreshJob(id = 1L, triggeredBy = "test", totalAssets = 0)

    @BeforeEach
    fun setUp() {
        every { vulnerabilityConfigService.getReminderOneDays() } returns 30
        // Relaxed mocks return plain Object for generic signatures, which the
        // Micronaut Data repository interface then fails to cast — stub explicitly.
        every { refreshJobRepository.update(any()) } answers { firstArg() }
        every { outdatedAssetRepository.saveAll(any<List<com.secman.domain.OutdatedAssetMaterializedView>>()) } answers { firstArg() }
        service = MaterializedViewRefreshService(
            refreshJobRepository,
            outdatedAssetRepository,
            vulnerabilityRepository,
            vulnerabilityConfigService,
            eventPublisher,
            vulnerabilityStatisticsCacheService,
            assetHeatmapService,
            vulnerabilityService,
            awsCleanServerKpiService
        )
        // In production Micronaut injects the AOP self-proxy; in unit tests the plain
        // instance is fine (no transactionality to assert here). The field is private
        // (public/internal visibility crashes the bean graph), so set via reflection.
        MaterializedViewRefreshService::class.java.getDeclaredField("selfProvider").apply {
            isAccessible = true
            set(service, jakarta.inject.Provider { service })
        }
    }

    private fun overdueVulnerability(): Vulnerability {
        val asset = Asset(id = 42L, name = "server-1", type = "SERVER", owner = "ops")
        return Vulnerability(
            id = 100L,
            asset = asset,
            vulnerabilityId = "CVE-2026-0001",
            cvssSeverity = "High",
            scanTimestamp = LocalDateTime.now().minusDays(45),
            firstSeenAt = LocalDateTime.now().minusDays(45)
        )
    }

    @Test
    @DisplayName("preserves the previous snapshot when computing the new one fails")
    fun refreshFailurePreservesPreviousViewContents() {
        every { vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(any()) } throws
            RuntimeException("db connection lost mid-refresh")

        assertThatThrownBy { service.executeRefresh(job) }
            .hasMessageContaining("db connection lost")

        verify(exactly = 0) { outdatedAssetRepository.deleteAll() }
        verify(exactly = 0) { outdatedAssetRepository.saveAll(any<List<com.secman.domain.OutdatedAssetMaterializedView>>()) }
    }

    @Test
    @DisplayName("only clears the view after the new snapshot has been computed")
    fun clearHappensAfterComputation() {
        every { vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(any()) } returns
            listOf(overdueVulnerability())

        service.executeRefresh(job)

        verifyOrder {
            vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(any())
            outdatedAssetRepository.deleteAll()
            outdatedAssetRepository.saveAll(any<List<com.secman.domain.OutdatedAssetMaterializedView>>())
        }
    }

    @Test
    @DisplayName("writes the new snapshot rows when overdue vulnerabilities exist")
    fun refreshWritesSnapshotRows() {
        every { vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(any()) } returns
            listOf(overdueVulnerability())

        val saved = mutableListOf<List<com.secman.domain.OutdatedAssetMaterializedView>>()
        every { outdatedAssetRepository.saveAll(capture(saved)) } answers { firstArg() }

        service.executeRefresh(job)

        assertThat(saved.flatten()).hasSize(1)
        assertThat(saved.flatten().single().assetId).isEqualTo(42L)
        assertThat(saved.flatten().single().highCount).isEqualTo(1)
    }

    @Test
    @DisplayName("oldestVulnDays uses the firstSeenAt SLA anchor, not the re-import-refreshed scanTimestamp")
    fun oldestVulnDaysUsesFirstSeenAnchor() {
        val vuln = overdueVulnerability().apply {
            firstSeenAt = LocalDateTime.now().minusDays(45)
            scanTimestamp = LocalDateTime.now().minusDays(5) // refreshed by a re-import
        }
        every { vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(any()) } returns listOf(vuln)

        val saved = mutableListOf<List<com.secman.domain.OutdatedAssetMaterializedView>>()
        every { outdatedAssetRepository.saveAll(capture(saved)) } answers { firstArg() }

        service.executeRefresh(job)

        assertThat(saved.flatten().single().oldestVulnDays).isEqualTo(45)
    }

    @Test
    @DisplayName("still clears stale rows when nothing is overdue anymore")
    fun emptyResultClearsView() {
        every { vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(any()) } returns emptyList()

        service.executeRefresh(job)

        verify(exactly = 1) { outdatedAssetRepository.deleteAll() }
    }

    @Test
    @DisplayName("excludes vulnerabilities already flagged excepted, using the materialized column directly")
    fun excludesExceptedVulnerabilities() {
        val excepted = overdueVulnerability().apply { excepted = true }
        every { vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(any()) } returns listOf(excepted)

        service.executeRefresh(job)

        verify(exactly = 1) { outdatedAssetRepository.deleteAll() }
        verify(exactly = 0) { outdatedAssetRepository.saveAll(any<List<com.secman.domain.OutdatedAssetMaterializedView>>()) }
    }

    @Test
    @DisplayName("passes the already-loaded overdue vulnerabilities into the AWS clean-server KPI recalculation")
    fun sharesPreloadedVulnerabilitiesWithAwsKpiStep() {
        val vuln = overdueVulnerability()
        every { vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(any()) } returns listOf(vuln)

        service.executeRefresh(job)

        verify(exactly = 1) { awsCleanServerKpiService.recalculate(listOf(vuln)) }
    }
}
