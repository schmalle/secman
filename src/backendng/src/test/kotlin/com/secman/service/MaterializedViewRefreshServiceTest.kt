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

    // In-memory fake backing the job repository, so trigger/execute/sweep tests can
    // exercise real save -> findById -> findRunningJob(s) round-trips instead of
    // stubbing each call's return value by hand.
    private val jobStore = mutableListOf<MaterializedViewRefreshJob>()
    private var nextId = 1L

    @BeforeEach
    fun setUp() {
        every { vulnerabilityConfigService.getReminderOneDays() } returns 30
        // Relaxed mocks return plain Object for generic signatures, which the
        // Micronaut Data repository interface then fails to cast — stub explicitly.
        every { refreshJobRepository.update(any()) } answers { firstArg() }
        every { outdatedAssetRepository.saveAll(any<List<com.secman.domain.OutdatedAssetMaterializedView>>()) } answers { firstArg() }
        every { refreshJobRepository.save(any()) } answers {
            val saved = firstArg<MaterializedViewRefreshJob>()
            if (saved.id == null) saved.id = nextId++
            jobStore.add(saved)
            saved
        }
        every { refreshJobRepository.findById(any()) } answers {
            java.util.Optional.ofNullable(jobStore.find { it.id == firstArg<Long>() })
        }
        every { refreshJobRepository.findRunningJob() } answers {
            java.util.Optional.ofNullable(
                jobStore.filter { it.status == com.secman.domain.RefreshJobStatus.RUNNING }
                    .maxByOrNull { it.startedAt }
            )
        }
        every { refreshJobRepository.findRunningJobs() } answers {
            jobStore.filter { it.status == com.secman.domain.RefreshJobStatus.RUNNING }.sortedBy { it.id }
        }
        // Default: no cooldown, so tests unrelated to debouncing keep seeing the
        // pre-existing "every trigger starts immediately" behavior.
        service = buildService(minRefreshIntervalSeconds = 0L)
    }

    /**
     * Builds a service instance wired to the shared mocks/fakes above, with the given
     * cooldown. Cooldown-specific tests build their own instance here instead of using
     * the default 0-second [service] from [setUp], so deferral is deterministic without
     * needing to mock the wall clock.
     */
    private fun buildService(minRefreshIntervalSeconds: Long): MaterializedViewRefreshService {
        val built = MaterializedViewRefreshService(
            refreshJobRepository,
            outdatedAssetRepository,
            vulnerabilityRepository,
            vulnerabilityConfigService,
            eventPublisher,
            vulnerabilityStatisticsCacheService,
            assetHeatmapService,
            vulnerabilityService,
            awsCleanServerKpiService,
            minRefreshIntervalSeconds
        )
        // In production Micronaut injects the AOP self-proxy; in unit tests the plain
        // instance is fine (no transactionality to assert here). The field is private
        // (public/internal visibility crashes the bean graph), so set via reflection.
        MaterializedViewRefreshService::class.java.getDeclaredField("selfProvider").apply {
            isAccessible = true
            set(built, jakarta.inject.Provider { built })
        }
        return built
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

    @Test
    @DisplayName("defers a second trigger within the cooldown window instead of starting a new job")
    fun secondTriggerWithinCooldownIsDeferredAndReturnsLastCompletedJob() {
        val cooldownService = buildService(minRefreshIntervalSeconds = 3600L)

        val first = cooldownService.triggerAsyncRefresh("first")
        val second = cooldownService.triggerAsyncRefresh("second")

        assertThat(second.id).isEqualTo(first.id)
        assertThat(jobStore).hasSize(1)
    }

    @Test
    @DisplayName("bypassCooldown always starts a new job, even within the cooldown window")
    fun bypassCooldownAlwaysStartsANewJob() {
        val cooldownService = buildService(minRefreshIntervalSeconds = 3600L)

        val first = cooldownService.triggerAsyncRefresh("first")
        val second = cooldownService.triggerAsyncRefresh("Manual refresh by admin", bypassCooldown = true)

        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(jobStore).hasSize(2)
    }

    @Test
    @DisplayName("sweep drains a pending trigger and starts a new job once the cooldown has elapsed")
    fun sweepDrainsPendingTriggerOnceCooldownElapses() {
        val cooldownService = buildService(minRefreshIntervalSeconds = 60L)

        val first = cooldownService.triggerAsyncRefresh("first")
        cooldownService.triggerAsyncRefresh("second") // deferred: within cooldown
        assertThat(jobStore).hasSize(1)

        // Move the cooldown anchor into the past instead of mocking the wall clock —
        // `first` is the same job instance the service tracks as lastCompletedJob.
        first.completedAt = LocalDateTime.now().minusHours(2)

        cooldownService.sweepPendingRefreshTrigger()

        assertThat(jobStore).hasSize(2)
        assertThat(pendingTriggerReasonOf(cooldownService)).isNull()
    }

    @Test
    @DisplayName("sweep is a no-op while a refresh job is currently running")
    fun sweepIsNoOpWhileAJobIsRunning() {
        val cooldownService = buildService(minRefreshIntervalSeconds = 60L)
        jobStore.add(MaterializedViewRefreshJob(id = 99L, triggeredBy = "stuck", totalAssets = 0))
        setPendingTriggerReasonOf(cooldownService, "queued while running")

        cooldownService.sweepPendingRefreshTrigger()

        assertThat(jobStore).hasSize(1)
        assertThat(pendingTriggerReasonOf(cooldownService)).isEqualTo("queued while running")
    }

    @Test
    @DisplayName("sweep is a no-op before the cooldown has elapsed")
    fun sweepIsNoOpBeforeCooldownElapses() {
        val cooldownService = buildService(minRefreshIntervalSeconds = 3600L)

        cooldownService.triggerAsyncRefresh("first")
        cooldownService.triggerAsyncRefresh("second") // deferred: within cooldown
        assertThat(jobStore).hasSize(1)

        cooldownService.sweepPendingRefreshTrigger() // completedAt is seconds old, well within 3600s cooldown

        assertThat(jobStore).hasSize(1)
        assertThat(pendingTriggerReasonOf(cooldownService)).isEqualTo("second")
    }

    @Test
    @DisplayName("a failed refresh still updates the cooldown anchor, so it doesn't retry back-to-back")
    fun failedRefreshStillUpdatesCooldownAnchor() {
        every { vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(any()) } throws RuntimeException("boom")
        val cooldownService = buildService(minRefreshIntervalSeconds = 3600L)

        val first = cooldownService.triggerAsyncRefresh("first")
        val second = cooldownService.triggerAsyncRefresh("second")

        assertThat(second.id).isEqualTo(first.id)
        assertThat(jobStore).hasSize(1)
    }

    private fun pendingTriggerReasonOf(target: MaterializedViewRefreshService): String? {
        val field = MaterializedViewRefreshService::class.java.getDeclaredField("pendingTriggerReason")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(target) as java.util.concurrent.atomic.AtomicReference<String?>).get()
    }

    private fun setPendingTriggerReasonOf(target: MaterializedViewRefreshService, reason: String) {
        val field = MaterializedViewRefreshService::class.java.getDeclaredField("pendingTriggerReason")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(target) as java.util.concurrent.atomic.AtomicReference<String?>).set(reason)
    }
}
