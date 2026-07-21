package com.secman.service

import com.secman.domain.MaterializedViewRefreshJob
import com.secman.domain.OutdatedAssetMaterializedView
import com.secman.domain.RefreshProgressEvent
import com.secman.repository.MaterializedViewRefreshJobRepository
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.context.annotation.Value
import io.micronaut.context.event.ApplicationEventPublisher
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import io.micronaut.scheduling.annotation.Async
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Service for managing materialized view refresh operations
 *
 * Provides asynchronous refresh of outdated assets materialized view with:
 * - Background job execution (@Async)
 * - Progress tracking and SSE event publishing
 * - Batch processing for performance (1000 assets/chunk)
 * - Error handling and audit trail
 * - Observability metrics and structured logging
 *
 * Feature: 034-outdated-assets
 * Task: T011
 * Spec reference: FR-005, FR-007, FR-021, FR-022, research.md
 */
@Singleton
open class MaterializedViewRefreshService(
    private val refreshJobRepository: MaterializedViewRefreshJobRepository,
    private val outdatedAssetRepository: OutdatedAssetMaterializedViewRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val vulnerabilityConfigService: VulnerabilityConfigService,
    private val eventPublisher: ApplicationEventPublisher<RefreshProgressEvent>,
    private val vulnerabilityStatisticsCacheService: VulnerabilityStatisticsCacheService,
    private val assetHeatmapService: AssetHeatmapService,
    private val vulnerabilityService: VulnerabilityService,
    private val awsCleanServerKpiService: AwsCleanServerKpiService,
    @Value("\${secman.materialized-view-refresh.min-interval-seconds:60}")
    private val minRefreshIntervalSeconds: Long
) {
    private val log = LoggerFactory.getLogger(MaterializedViewRefreshService::class.java)

    /**
     * Provider for self-reference so @Transactional/@Async apply on internal method calls
     * (same AOP-proxy-bypass fix as CrowdStrikeVulnerabilityImportService, Feature 053).
     * Without this, executeRefresh() invoking swapMaterializedView() on `this` bypasses the
     * proxy and the delete+insert swap runs as two separate transactions — readers can then
     * observe an empty half-swapped view.
     */
    @jakarta.inject.Inject
    private lateinit var selfProvider: jakarta.inject.Provider<MaterializedViewRefreshService>

    // SSE sink for broadcasting refresh progress to all connected clients
    // Many().multicast() allows multiple subscribers
    private val progressSink: Sinks.Many<RefreshProgressEvent> = Sinks.many().multicast().onBackpressureBuffer()

    /**
     * Reason for a trigger that arrived while a refresh could not start immediately
     * (a job is already running, or the cooldown since the last completed cycle hasn't
     * elapsed). Drained by [sweepPendingRefreshTrigger] once both conditions clear,
     * guaranteeing the latest such trigger eventually runs instead of being dropped by
     * the cooldown gate below. A newer reason simply overwrites an older one — only
     * "did anything change since the last cycle" matters, not a queue of every trigger.
     */
    private val pendingTriggerReason = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** Anchor for the cooldown gate: the most recently completed (or failed) refresh job. */
    private val lastCompletedJob = java.util.concurrent.atomic.AtomicReference<MaterializedViewRefreshJob?>(null)

    companion object {
        private const val BATCH_SIZE = 1000
    }

    /**
     * Trigger asynchronous materialized view refresh with concurrency control
     *
     * Creates a refresh job and executes refresh in background.
     * Returns immediately (non-blocking).
     *
     * **Concurrency Control (Performance Optimization):**
     * If a refresh job is already running, this method returns the existing job
     * instead of starting a new one. This prevents multiple concurrent refreshes
     * from wasting resources when triggered by batch operations (e.g., CLI imports).
     *
     * Task: T011
     * Spec reference: FR-005 (async refresh)
     *
     * @param triggeredBy Description of what triggered the refresh (for audit trail)
     * @param bypassCooldown Skip the min-interval cooldown gate below. Only the manual
     *   admin "Refresh Now" endpoint sets this — an explicit request should never be
     *   silently deferred; every other caller (e.g. CrowdStrike import, once per
     *   sub-batch HTTP request) is subject to the cooldown so a long import doesn't
     *   run this service's full-table-scale refresh chain back-to-back for its whole
     *   duration (2026-07-21 incident: HikariCP pool starvation from exactly that).
     * @return Either the newly created job, the existing running job (if one is in
     *   progress), or the last completed job (if deferred by the cooldown)
     */
    fun triggerAsyncRefresh(triggeredBy: String, bypassCooldown: Boolean = false): MaterializedViewRefreshJob {
        log.info("Triggering async refresh: triggeredBy={}, bypassCooldown={}", triggeredBy, bypassCooldown)

        // Concurrency control: Check if a refresh is already running
        val runningJob = getCurrentRunningJob()
        if (runningJob != null) {
            // Recover from stale RUNNING jobs: if the job is older than the
            // longest plausible refresh window (1 hour), the worker thread is
            // gone (process restart, OOM, etc.). Mark it failed and let a new
            // trigger proceed — otherwise the system is wedged forever.
            val ageMinutes = ChronoUnit.MINUTES.between(runningJob.startedAt, LocalDateTime.now())
            if (ageMinutes > 60) {
                log.warn("Recovering stale RUNNING job: jobId={}, ageMinutes={}, triggeredBy={}",
                    runningJob.id, ageMinutes, runningJob.triggeredBy)
                runningJob.markFailed("Job marked failed during recovery — exceeded 60-minute runtime threshold (likely orphaned by process restart)")
                refreshJobRepository.update(runningJob)
                // Fall through to start a fresh job
            } else {
                log.info("Skipping refresh trigger - job already running: jobId={}, triggeredBy={}, progress={}%",
                    runningJob.id, runningJob.triggeredBy, runningJob.progressPercentage)
                if (!bypassCooldown) {
                    pendingTriggerReason.set(triggeredBy)
                }
                return runningJob
            }
        }

        // Cooldown gate: space refresh cycles at least minRefreshIntervalSeconds apart.
        // A trigger that arrives too soon is remembered (not lost) and drained later by
        // sweepPendingRefreshTrigger() once the cooldown elapses.
        if (!bypassCooldown) {
            val last = lastCompletedJob.get()
            val lastFinishedAt = last?.completedAt
            if (lastFinishedAt != null) {
                val elapsedSeconds = ChronoUnit.SECONDS.between(lastFinishedAt, LocalDateTime.now())
                if (elapsedSeconds < minRefreshIntervalSeconds) {
                    pendingTriggerReason.set(triggeredBy)
                    log.info(
                        "Deferring refresh trigger (cooldown active): triggeredBy={}, elapsedSeconds={}, minIntervalSeconds={}",
                        triggeredBy, elapsedSeconds, minRefreshIntervalSeconds
                    )
                    return last
                }
            }
        }

        // Create job entity
        val job = MaterializedViewRefreshJob(
            triggeredBy = triggeredBy,
            totalAssets = 0  // Will be calculated during refresh
        )
        val savedJob = refreshJobRepository.save(job)

        // Re-check after insert: the running-job read above is not atomic with the save, so
        // two concurrent triggers can both pass it and both insert a RUNNING job. The job with
        // the lowest id wins; any younger duplicate marks itself failed and defers to the winner,
        // guaranteeing at most one live refresh (and one atomic view swap) at a time.
        val oldestRunning = refreshJobRepository.findRunningJobs().firstOrNull()
        if (oldestRunning != null && oldestRunning.id != savedJob.id) {
            log.info("Concurrent refresh trigger lost the race - deferring to running job: jobId={}, winnerJobId={}",
                savedJob.id, oldestRunning.id)
            savedJob.markFailed("Duplicate trigger - refresh job ${oldestRunning.id} was already running")
            refreshJobRepository.update(savedJob)
            return oldestRunning
        }

        // Execute refresh asynchronously (via self proxy so @Async applies on the internal call)
        selfProvider.get().executeRefreshAsync(savedJob.id!!)

        return savedJob
    }

    /**
     * Execute refresh in background thread
     *
     * Task: T011
     * Spec reference: FR-005, FR-007, FR-021, FR-022
     */
    @Async
    open fun executeRefreshAsync(jobId: Long) {
        val job = refreshJobRepository.findById(jobId).orElseThrow()

        try {
            log.info("Starting async refresh job: jobId={}, triggeredBy={}", jobId, job.triggeredBy)

            executeRefresh(job)

            log.info("Async refresh job completed: jobId={}, durationMs={}", jobId, job.durationMs)
        } catch (e: Exception) {
            log.error("Async refresh job failed: jobId={}, error={}", jobId, e.message, e)
            job.markFailed(e.message ?: "Unknown error")
            refreshJobRepository.update(job)

            // Publish failure event
            publishProgressEvent(job, "Refresh failed: ${e.message}")
        } finally {
            // Anchors the cooldown gate in triggerAsyncRefresh() — set on both success
            // and failure so a repeatedly-failing refresh still gets spaced out instead
            // of retrying back-to-back.
            lastCompletedJob.set(job)
        }
    }

    /**
     * Execute materialized view refresh with progress tracking
     *
     * **Performance Optimization (Fix 3):**
     * Uses batch query to load all overdue vulnerabilities in ONE database call,
     * eliminating the N+1 query pattern where we previously queried per-asset.
     *
     * Task: T011, T060 (progress publishing), T061 (batch processing)
     * Spec reference: FR-005, FR-007
     *
     * Note: NOT @Transactional - uses separate short transactions for each operation
     * to avoid holding database locks during long-running refresh process
     */
    open fun executeRefresh(job: MaterializedViewRefreshJob) {
        val thresholdDays = vulnerabilityConfigService.getReminderOneDays()
        log.info("Executing refresh with threshold: {} days", thresholdDays)

        // Step 1: Load ALL overdue vulnerabilities in ONE query (Fix 3 - eliminates N+1 pattern).
        // Loaded using whichever threshold is more inclusive of this refresh's own reminderOneDays
        // window and the AWS clean-server KPI's fixed 30-day window (a smaller day-count means an
        // earlier date, a superset), so refreshDerivedData() can reuse this same result instead of
        // re-querying + re-loading the whole overdue-vulnerability set a second time.
        val thresholdDate = LocalDateTime.now().minusDays(thresholdDays.toLong())
        val loadThresholdDate = LocalDateTime.now()
            .minusDays(minOf(thresholdDays.toLong(), AwsCleanServerKpiService.VULN_AGE_THRESHOLD_DAYS))
        log.debug("Fetching all vulnerabilities older than {} (threshold: {} days)", thresholdDate, thresholdDays)

        val allOverdueVulnerabilities = vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(loadThresholdDate)
        log.info("Loaded {} overdue vulnerabilities in single query", allOverdueVulnerabilities.size)

        // Step 2: Group vulnerabilities by asset and filter exceptions.
        // `excepted` is already materialized on the entity (kept fresh by ExceptionMaterializationService
        // on every exception create/update/delete plus an hourly expiry sweep) — no per-row DB call needed.
        val vulnerabilitiesByAsset = allOverdueVulnerabilities
            .asSequence()
            .filter { (it.firstSeenAt ?: it.scanTimestamp) < thresholdDate }
            .groupBy { it.asset }

        // Filter to assets with at least one non-excepted overdue vulnerability
        val assetsWithOverdueVulns = vulnerabilitiesByAsset.mapNotNull { (asset, vulns) ->
            val nonExceptedVulns = vulns.filter { vuln -> !vuln.excepted }
            if (nonExceptedVulns.isNotEmpty()) {
                asset to nonExceptedVulns
            } else {
                null
            }
        }.toMap()

        job.totalAssets = assetsWithOverdueVulns.size
        selfProvider.get().updateJob(job)

        log.info("Found {} assets with overdue vulnerabilities (after exception filtering)", assetsWithOverdueVulns.size)

        // Step 3: Build the complete new snapshot in memory, with progress updates.
        // No database write happens until the snapshot is fully computed.
        val assetEntries = assetsWithOverdueVulns.entries.toList()
        val materializedRecords = mutableListOf<OutdatedAssetMaterializedView>()
        assetEntries.chunked(BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            batch.mapTo(materializedRecords) { (asset, overdueVulns) ->
                createMaterializedRecordFromVulns(asset, overdueVulns)
            }

            // Update progress
            val processed = ((batchIndex + 1) * BATCH_SIZE).coerceAtMost(assetsWithOverdueVulns.size)
            job.updateProgress(processed)
            selfProvider.get().updateJob(job)

            // Publish progress event
            publishProgressEvent(job, "Processing assets...")

            log.debug("Processed batch {}: {} assets", batchIndex + 1, processed)
        }

        // Step 4: Atomically replace the previous snapshot. Readers (user dashboard
        // "Overdue Patching", Outdated Assets page) always see either the old complete
        // snapshot or the new one — never an empty or partial view. A crash before
        // this point leaves the previous snapshot intact. The write phase is small
        // (one row per outdated asset), so a single transaction is cheap; only the
        // read/compute phase above must stay outside a transaction.
        // Invoked via the self proxy - a direct call on `this` would bypass the AOP
        // interceptor and split the delete+insert into two separate transactions.
        selfProvider.get().swapMaterializedView(materializedRecords)

        // Step 5: Mark job as completed
        job.markCompleted()
        selfProvider.get().updateJob(job)

        // Step 6 & 7: Refresh derived data (statistics cache + heatmap)
        refreshDerivedData(allOverdueVulnerabilities)

        // Publish completion event
        publishProgressEvent(job,
            if (materializedRecords.isEmpty()) "Refresh completed: no outdated assets"
            else "Refresh completed successfully")

        log.info("Refresh completed: jobId={}, assetsProcessed={}, durationMs={}",
            job.id, job.assetsProcessed, job.durationMs)
    }

    /**
     * Replace the whole materialized view with the freshly computed snapshot in one
     * transaction, so concurrent readers never observe a cleared-but-not-yet-rebuilt
     * view (the cause of the dashboard's false "0 overdue assets").
     */
    @Transactional
    open fun swapMaterializedView(records: List<OutdatedAssetMaterializedView>) {
        outdatedAssetRepository.deleteAll()
        if (records.isNotEmpty()) {
            outdatedAssetRepository.saveAll(records)
        }
    }

    /**
     * Update job status in a separate transaction
     */
    @Transactional
    open fun updateJob(job: MaterializedViewRefreshJob) {
        refreshJobRepository.update(job)
    }

    /**
     * Refresh derived data that piggybacks on the materialized view refresh lifecycle.
     * Always runs after import, regardless of whether overdue assets exist.
     *
     * The full-table `excepted` drift-safety-net recompute previously ran here on every single
     * refresh (~124s-class). It's now a daily scheduled job
     * (ExceptionMaterializationService.recomputeAllExceptedScheduled) instead, since the incremental
     * per-CRUD recompute and hourly expiry sweep already keep `excepted` fresh for the common case.
     *
     * @param preloadedOverdueVulnerabilities the vulnerability set already loaded by executeRefresh(),
     *   reused here so the AWS clean-server KPI step doesn't re-query and re-load the same table.
     */
    private fun refreshDerivedData(preloadedOverdueVulnerabilities: List<com.secman.domain.Vulnerability>) {
        try {
            log.info("Refreshing vulnerability statistics cache after materialized view refresh")
            vulnerabilityStatisticsCacheService.refreshCache()
        } catch (e: Exception) {
            log.error("Statistics cache refresh failed (non-fatal): {}", e.message, e)
        }

        try {
            log.info("Refreshing asset heatmap after materialized view refresh")
            assetHeatmapService.recalculateHeatmap()
        } catch (e: Exception) {
            log.error("Asset heatmap refresh failed (non-fatal): {}", e.message, e)
        }

        try {
            log.info("Warming admin not-excepted vulnerability count cache after materialized view refresh")
            vulnerabilityService.getCachedNotExceptedCountAdmin()
        } catch (e: Exception) {
            log.error("Not-excepted count cache warm failed (non-fatal): {}", e.message, e)
        }

        try {
            log.info("Recalculating AWS clean-server KPI after materialized view refresh")
            awsCleanServerKpiService.recalculate(preloadedOverdueVulnerabilities)
        } catch (e: Exception) {
            log.error("AWS clean-server KPI recalculation failed (non-fatal): {}", e.message, e)
        }
    }

    /**
     * Create materialized record from pre-loaded vulnerability list
     *
     * **Performance Optimization (Fix 3):**
     * Takes pre-loaded vulnerabilities instead of querying per-asset,
     * eliminating N+1 database queries.
     *
     * Task: T011
     * Spec reference: data-model.md
     *
     * @param asset The asset entity (already loaded with workgroups)
     * @param overdueVulns Pre-filtered list of overdue, non-excepted vulnerabilities for this asset
     * @return OutdatedAssetMaterializedView record ready for persistence
     */
    private fun createMaterializedRecordFromVulns(
        asset: com.secman.domain.Asset,
        overdueVulns: List<com.secman.domain.Vulnerability>
    ): OutdatedAssetMaterializedView {
        // Calculate severity counts. cvssSeverity is stored title-case
        // ("Critical", "High", "Medium", "Low") by VulnerabilityService.addVulnerabilityFromCli
        // and by importers, so compare case-insensitively to avoid silently
        // collapsing every count to zero.
        val criticalCount = overdueVulns.count { it.cvssSeverity?.equals("CRITICAL", ignoreCase = true) == true }
        val highCount = overdueVulns.count { it.cvssSeverity?.equals("HIGH", ignoreCase = true) == true }
        val mediumCount = overdueVulns.count { it.cvssSeverity?.equals("MEDIUM", ignoreCase = true) == true }
        val lowCount = overdueVulns.count { it.cvssSeverity?.equals("LOW", ignoreCase = true) == true }

        // Find oldest vulnerability. Anchor on firstSeenAt (falls back to scanTimestamp
        // for legacy rows) to match VulnerabilityService.calculateOverdueStatus — scanTimestamp
        // alone gets refreshed on every re-import and would understate the true SLA age.
        val now = LocalDateTime.now()
        val oldestVuln = overdueVulns.maxByOrNull { vuln ->
            ChronoUnit.DAYS.between(vuln.firstSeenAt ?: vuln.scanTimestamp, now)
        }
        val oldestVulnDays = oldestVuln?.let {
            ChronoUnit.DAYS.between(it.firstSeenAt ?: it.scanTimestamp, now).toInt()
        } ?: 0

        // Get workgroup IDs (denormalized for performance) - already loaded via JOIN FETCH
        val workgroupIds = asset.workgroups.joinToString(",") { it.id.toString() }

        return OutdatedAssetMaterializedView(
            assetId = asset.id!!,
            assetName = asset.name,
            assetType = asset.type,
            totalOverdueCount = overdueVulns.size,
            criticalCount = criticalCount,
            highCount = highCount,
            mediumCount = mediumCount,
            lowCount = lowCount,
            oldestVulnDays = oldestVulnDays,
            oldestVulnId = oldestVuln?.vulnerabilityId,
            workgroupIds = workgroupIds,
            adDomain = asset.adDomain,
            lastCalculatedAt = now
        )
    }

    /**
     * Publish progress event for SSE streaming
     *
     * Task: T011, T060
     * Spec reference: FR-007, FR-022
     */
    private fun publishProgressEvent(job: MaterializedViewRefreshJob, message: String) {
        val event = RefreshProgressEvent(
            jobId = job.id!!,
            status = job.status,
            progressPercentage = job.progressPercentage,
            assetsProcessed = job.assetsProcessed,
            totalAssets = job.totalAssets,
            message = message
        )

        eventPublisher.publishEvent(event)

        // Also emit to SSE stream for real-time updates
        progressSink.tryEmitNext(event)

        log.debug("Published progress event: jobId={}, status={}, progress={}%",
            job.id, job.status, job.progressPercentage)
    }

    /**
     * Get SSE stream of refresh progress events
     *
     * Task: T050-T053
     * User Story: US3 - Manual Refresh
     *
     * @return Flux of progress events
     */
    fun getProgressStream(): Flux<RefreshProgressEvent> {
        return progressSink.asFlux()
    }

    /**
     * Get currently running refresh job (if any)
     *
     * Task: T054-T055
     * User Story: US3 - Manual Refresh
     *
     * @return Running job or null
     */
    fun getCurrentRunningJob(): MaterializedViewRefreshJob? {
        return refreshJobRepository.findRunningJob().orElse(null)
    }

    /**
     * Get recent refresh job history
     *
     * Task: T056-T057
     * User Story: US3 - Manual Refresh
     *
     * @param limit Maximum number of jobs to return
     * @return List of recent jobs, newest first
     */
    fun getRecentJobs(limit: Int = 10): List<MaterializedViewRefreshJob> {
        val pageable = Pageable.from(0, limit, Sort.of(Sort.Order.desc("startedAt")))
        return refreshJobRepository.findAll(pageable).content
    }

    /**
     * Drains a debounced refresh trigger once it's safe to start: no job currently
     * running, and the cooldown since the last completed cycle has elapsed. This is
     * what guarantees the latest trigger received during a burst (e.g. a long
     * CrowdStrike import posting many sub-batches) eventually runs, instead of being
     * permanently dropped by the cooldown gate in [triggerAsyncRefresh].
     *
     * 15s is intentionally much shorter than the cooldown itself, so the extra
     * staleness this sweep can introduce is bounded and negligible relative to the
     * cooldown — it isn't separately configurable to keep the config surface small.
     */
    @Scheduled(fixedDelay = "15s")
    open fun sweepPendingRefreshTrigger() {
        val reason = pendingTriggerReason.get() ?: return
        if (getCurrentRunningJob() != null) return

        val lastFinishedAt = lastCompletedJob.get()?.completedAt
        if (lastFinishedAt != null) {
            val elapsedSeconds = ChronoUnit.SECONDS.between(lastFinishedAt, LocalDateTime.now())
            if (elapsedSeconds < minRefreshIntervalSeconds) return
        }

        // CAS so a trigger racing this sweep tick can't have its (newer) reason
        // silently dropped by a plain get-then-clear.
        if (pendingTriggerReason.compareAndSet(reason, null)) {
            log.info("Draining debounced refresh trigger after cooldown: reason={}", reason)
            triggerAsyncRefresh(reason)
        }
    }
}
