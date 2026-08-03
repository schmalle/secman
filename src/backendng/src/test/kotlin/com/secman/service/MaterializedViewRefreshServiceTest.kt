package com.secman.service

import com.secman.domain.MaterializedViewRefreshJob
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
    private val vulnerabilityConfigService = mockk<VulnerabilityConfigService>()
    private val eventPublisher = mockk<io.micronaut.context.event.ApplicationEventPublisher<com.secman.domain.RefreshProgressEvent>>(relaxed = true)
    private val vulnerabilityStatisticsCacheService = mockk<VulnerabilityStatisticsCacheService>(relaxed = true)
    private val assetHeatmapService = mockk<AssetHeatmapService>(relaxed = true)
    private val vulnerabilityService = mockk<VulnerabilityService>(relaxed = true)
    private val awsCleanServerKpiService = mockk<AwsCleanServerKpiService>(relaxed = true)
    private val edrCoverageKpiService = mockk<EdrCoverageKpiService>(relaxed = true)

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
    private fun buildService(
        minRefreshIntervalSeconds: Long,
        // 0 = no quiet period, so most tests can sweep immediately. Tests that exercise the
        // import-coalescing behaviour pass a real value.
        quietPeriodSeconds: Long = 0L
    ): MaterializedViewRefreshService {
        val built = MaterializedViewRefreshService(
            refreshJobRepository,
            outdatedAssetRepository,
            vulnerabilityConfigService,
            eventPublisher,
            vulnerabilityStatisticsCacheService,
            assetHeatmapService,
            vulnerabilityService,
            awsCleanServerKpiService,
            edrCoverageKpiService,
            minRefreshIntervalSeconds,
            quietPeriodSeconds
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

    /**
     * The progress sink must survive subscriber churn. With Reactor's default no-arg
     * `onBackpressureBuffer()` (autoCancel = true) the sink terminated permanently once the last
     * subscriber disconnected, so on this long-lived singleton the admin progress stream worked
     * exactly once per backend process — and silently, since tryEmitNext still returned OK.
     */
    @Test
    @DisplayName("progress stream still delivers to a new subscriber after a previous one disconnects")
    fun progressStreamSurvivesSubscriberChurn() {
        val firstSeen = mutableListOf<com.secman.domain.RefreshProgressEvent>()
        val firstSub = service.getProgressStream().subscribe { firstSeen.add(it) }

        every { outdatedAssetRepository.countAssetsWithOverdueVulnerabilities(any()) } returns 1L
        every { outdatedAssetRepository.rebuildFromOverdueVulnerabilities(any(), any()) } returns 1

        service.executeRefresh(job)
        assertThat(firstSeen).isNotEmpty()

        // The admin closes the Outdated Assets page.
        firstSub.dispose()

        // A second admin opens it and triggers another refresh.
        val secondSeen = mutableListOf<com.secman.domain.RefreshProgressEvent>()
        val secondSub = service.getProgressStream().subscribe { secondSeen.add(it) }
        service.executeRefresh(MaterializedViewRefreshJob(id = 2L, triggeredBy = "second", totalAssets = 0))
        secondSub.dispose()

        assertThat(secondSeen).isNotEmpty()
    }

    @Test
    @DisplayName("does not touch the view when sizing the rebuild fails")
    fun refreshFailureBeforeSwapPreservesPreviousViewContents() {
        every { outdatedAssetRepository.countAssetsWithOverdueVulnerabilities(any()) } throws
            RuntimeException("db connection lost mid-refresh")

        assertThatThrownBy { service.executeRefresh(job) }
            .hasMessageContaining("db connection lost")

        verify(exactly = 0) { outdatedAssetRepository.deleteAll() }
        verify(exactly = 0) { outdatedAssetRepository.rebuildFromOverdueVulnerabilities(any(), any()) }
    }

    @Test
    @DisplayName("clears and rebuilds in that order, inside one swap call")
    fun clearIsImmediatelyFollowedByRebuild() {
        every { outdatedAssetRepository.countAssetsWithOverdueVulnerabilities(any()) } returns 1L
        every { outdatedAssetRepository.rebuildFromOverdueVulnerabilities(any(), any()) } returns 1

        service.executeRefresh(job)

        // Atomicity no longer comes from "compute everything before deleting" — there is nothing
        // to compute in heap any more. It comes from both statements sharing swapMaterializedView's
        // single @Transactional, so a failed rebuild rolls the delete back. That rollback guarantee
        // needs a real transaction and is covered by OutdatedAssetRebuildIntegrationTest.
        verifyOrder {
            outdatedAssetRepository.deleteAll()
            outdatedAssetRepository.rebuildFromOverdueVulnerabilities(any(), any())
        }
    }

    @Test
    @DisplayName("rebuilds with the configured overdue threshold and reports rows written on the job")
    fun refreshRebuildsAndRecordsRowsWritten() {
        every { outdatedAssetRepository.countAssetsWithOverdueVulnerabilities(any()) } returns 7L
        every { outdatedAssetRepository.rebuildFromOverdueVulnerabilities(any(), any()) } returns 7

        val thresholdSlot = mutableListOf<LocalDateTime>()
        val nowSlot = mutableListOf<LocalDateTime>()
        every { outdatedAssetRepository.rebuildFromOverdueVulnerabilities(capture(thresholdSlot), capture(nowSlot)) } returns 7

        service.executeRefresh(job)

        assertThat(job.totalAssets).isEqualTo(7)
        assertThat(job.assetsProcessed).isEqualTo(7)
        // getReminderOneDays() is stubbed to 30, so the threshold must be ~30 days before `now`.
        val daysBack = java.time.temporal.ChronoUnit.DAYS.between(thresholdSlot.single(), nowSlot.single())
        assertThat(daysBack).isEqualTo(30L)
    }

    @Test
    @DisplayName("still clears stale rows when nothing is overdue anymore")
    fun emptyResultClearsView() {
        every { outdatedAssetRepository.countAssetsWithOverdueVulnerabilities(any()) } returns 0L
        every { outdatedAssetRepository.rebuildFromOverdueVulnerabilities(any(), any()) } returns 0

        service.executeRefresh(job)

        verify(exactly = 1) { outdatedAssetRepository.deleteAll() }
    }

    @Test
    @DisplayName("triggers the AWS clean-server KPI without handing it any loaded vulnerability list")
    fun awsKpiStepIsInvokedWithoutSharingALoadedList() {
        // The KPI used to receive the refresh's overdue-vulnerability list so it could avoid its own
        // query, which forced that whole ~166k-entity result to stay reachable across the entire
        // refresh cycle (a contributor to the 2026-07-30 OOM). It now issues its own COUNT.
        every { outdatedAssetRepository.countAssetsWithOverdueVulnerabilities(any()) } returns 1L
        every { outdatedAssetRepository.rebuildFromOverdueVulnerabilities(any(), any()) } returns 1

        service.executeRefresh(job)

        verify(exactly = 1) { awsCleanServerKpiService.recalculate() }
        // The EDR-coverage KPI hangs off the same hook. Asserted here because a refresh that
        // silently stopped recalculating it would leave the dashboard reporting stale coverage
        // with nothing to indicate the number had gone cold.
        verify(exactly = 1) { edrCoverageKpiService.recalculate() }
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
        every { outdatedAssetRepository.countAssetsWithOverdueVulnerabilities(any()) } throws RuntimeException("boom")
        val cooldownService = buildService(minRefreshIntervalSeconds = 3600L)

        val first = cooldownService.triggerAsyncRefresh("first")
        val second = cooldownService.triggerAsyncRefresh("second")

        assertThat(second.id).isEqualTo(first.id)
        assertThat(jobStore).hasSize(1)
    }

    @Test
    @DisplayName("a burst of deferred requests from an import starts zero refreshes")
    fun deferredRequestsNeverStartARefreshInline() {
        val service = buildService(minRefreshIntervalSeconds = 0L, quietPeriodSeconds = 3600L)

        // Stands in for a CLI import posting ~94 sub-batches across 3 workers.
        repeat(94) { i -> service.requestDeferredRefresh("CLI Import - batch $i") }

        assertThat(jobStore).isEmpty()
        assertThat(pendingTriggerReasonOf(service)).isEqualTo("CLI Import - batch 93")
    }

    @Test
    @DisplayName("sweep holds the deferred refresh while requests are still arriving")
    fun sweepHoldsDeferredRefreshDuringQuietPeriod() {
        val service = buildService(minRefreshIntervalSeconds = 0L, quietPeriodSeconds = 3600L)

        service.requestDeferredRefresh("CLI Import")
        service.sweepPendingRefreshTrigger()

        assertThat(jobStore).isEmpty()
        assertThat(pendingTriggerReasonOf(service)).isEqualTo("CLI Import")
    }

    @Test
    @DisplayName("sweep starts exactly one refresh once the import has gone quiet")
    fun sweepStartsOneRefreshAfterQuietPeriodElapses() {
        // quietPeriodSeconds = 0 means "any elapsed time counts as quiet", which is how this
        // asserts the post-quiet-period behaviour without sleeping.
        val service = buildService(minRefreshIntervalSeconds = 0L, quietPeriodSeconds = 0L)

        repeat(5) { service.requestDeferredRefresh("CLI Import") }
        service.sweepPendingRefreshTrigger()

        assertThat(jobStore).hasSize(1)
        assertThat(pendingTriggerReasonOf(service)).isNull()

        // A second sweep with nothing new pending must not start another cycle.
        service.sweepPendingRefreshTrigger()
        assertThat(jobStore).hasSize(1)
    }

    @Test
    @DisplayName("the manual admin refresh still runs immediately during an import quiet period")
    fun manualRefreshBypassesTheQuietPeriod() {
        val service = buildService(minRefreshIntervalSeconds = 3600L, quietPeriodSeconds = 3600L)
        service.requestDeferredRefresh("CLI Import")

        service.triggerAsyncRefresh("Manual admin refresh", bypassCooldown = true)

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
