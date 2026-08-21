package com.secman.service

import com.secman.domain.MaterializedViewRefreshJob
import com.secman.domain.OutdatedAssetMaterializedView
import com.secman.domain.RefreshProgressEvent
import com.secman.repository.AssetRepository
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
    private val vulnerabilityConfigService: VulnerabilityConfigService,
    private val eventPublisher: ApplicationEventPublisher<RefreshProgressEvent>,
    private val vulnerabilityStatisticsCacheService: VulnerabilityStatisticsCacheService,
    private val assetHeatmapService: AssetHeatmapService,
    private val vulnerabilityService: VulnerabilityService,
    private val awsCleanServerKpiService: AwsCleanServerKpiService,
    private val edrCoverageKpiService: EdrCoverageKpiService,
    @Value("\${secman.materialized-view-refresh.min-interval-seconds:60}")
    private val minRefreshIntervalSeconds: Long,
    @Value("\${secman.materialized-view-refresh.quiet-period-seconds:120}")
    private val quietPeriodSeconds: Long
) {
    private val log = LoggerFactory.getLogger(MaterializedViewRefreshService::class.java)

    companion object {
        /**
         * Progress events buffered for a slow SSE subscriber. Matches Reactor's own
         * SMALL_BUFFER_SIZE default; stated explicitly so the bound is visible where the sink is
         * created rather than inherited from a no-arg overload.
         */
        private const val PROGRESS_SINK_BUFFER_SIZE = 256
    }

    /**
     * Provider for self-reference so @Transactional/@Async apply on internal method calls
     * (same AOP-proxy-bypass fix as CrowdStrikeVulnerabilityImportService, Feature 053).
     * Without this, executeRefresh() invoking swapMaterializedView() on `this` bypasses the
     * proxy and the delete+insert swap runs as two separate transactions — readers can then
     * observe an empty half-swapped view.
     */
    @jakarta.inject.Inject
    private lateinit var selfProvider: jakarta.inject.Provider<MaterializedViewRefreshService>

    /**
     * SSE sink broadcasting refresh progress to all connected admin clients.
     *
     * `autoCancel = false` is load-bearing. The default no-arg `onBackpressureBuffer()` is
     * `onBackpressureBuffer(SMALL_BUFFER_SIZE, autoCancel = true)`, which TERMINATES the sink for
     * the rest of the process lifetime as soon as the last subscriber disconnects. Since this is a
     * long-lived singleton, that meant the progress stream worked exactly once per backend start:
     * an admin opened the Outdated Assets page, watched one refresh, closed the tab — and every
     * later visitor's stream was silently dead. Silently, because `tryEmitNext` keeps returning
     * `OK` in that state, so nothing was logged and no error surfaced.
     *
     * The buffer bound is explicit rather than inherited, so it is visible at the call site. It is
     * a ceiling on events held for a slow subscriber, not a retention leak: with nobody subscribed
     * the sink reports `FAIL_ZERO_SUBSCRIBER` instead of accumulating.
     */
    private val progressSink: Sinks.Many<RefreshProgressEvent> =
        Sinks.many().multicast().onBackpressureBuffer(PROGRESS_SINK_BUFFER_SIZE, false)

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

    /**
     * Wall-clock of the most recent [requestDeferredRefresh], anchoring the quiet-period gate in
     * [sweepPendingRefreshTrigger]. Only consulted while [pendingTriggerReason] is set, so a
     * stale value here can never delay anything on its own.
     */
    private val lastDeferredRequestAt = java.util.concurrent.atomic.AtomicReference<LocalDateTime?>(null)

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
    /**
     * Register "data changed, refresh eventually" WITHOUT ever starting a refresh inline.
     *
     * This is the entry point for BULK writers — the CrowdStrike import and its reconcile sweep.
     * They call it once per sub-batch HTTP request, which for a full CLI import is ~94 times
     * (1881 servers / batchSize 20) across 3 concurrent workers over several minutes.
     *
     * [triggerAsyncRefresh]'s cooldown gate was not enough for that shape: it defers a trigger
     * only while the cooldown is *active*, so once `min-interval-seconds` elapsed the very next
     * sub-batch started a full refresh cycle — roughly once a minute, for the entire duration of
     * the import, each cycle scanning the whole vulnerability table while the import was still
     * writing to it. That amplification is what turned one expensive refresh into the sustained
     * heap and pool pressure behind the 2026-07-30 OutOfMemoryError.
     *
     * Here every call just bumps a timestamp. [sweepPendingRefreshTrigger] starts exactly ONE
     * refresh once `quiet-period-seconds` have passed with no further request — i.e. after the
     * import has actually stopped.
     *
     * Deliberately server-side rather than having the CLI flag its final batch: with
     * `parallelism = 3` there is no well-defined last batch, and this also covers older CLI
     * versions, manual callers, and the `/api/crowdstrike/vulnerabilities/save` path.
     */
    fun requestDeferredRefresh(triggeredBy: String) {
        pendingTriggerReason.set(triggeredBy)
        lastDeferredRequestAt.set(LocalDateTime.now())
        log.debug(
            "Deferred refresh requested (quiet period {}s): triggeredBy={}",
            quietPeriodSeconds, triggeredBy
        )
    }

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

        // Create job entity. triggered_by is VARCHAR(50): callers pass free-form reasons
        // (e.g. "Reconcile stale CrowdStrike vulns - 727637 rows cleared, 2074 agent-seen
        // stamps"), and an over-length value fails the INSERT with SQLState 22001 and kills
        // the whole refresh (real incident 2026-08-21) — truncate at the single write site.
        val job = MaterializedViewRefreshJob(
            triggeredBy = triggeredBy.take(50),
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
     * Execute materialized view refresh.
     *
     * The rebuild is a single server-side statement (see
     * OutdatedAssetMaterializedViewRepository.rebuildFromOverdueVulnerabilities), so this method's
     * heap cost is constant: no vulnerability or asset rows enter the JVM at all. It previously
     * loaded every overdue vulnerability as a managed entity and aggregated in Kotlin, which is
     * what ran out of heap during the 2026-07-30 import.
     *
     * **Progress reporting is now coarse.** A single statement has no observable intermediate
     * state, so the SSE stream reports the asset count up front (from a cheap COUNT) and then
     * jumps to complete, instead of ticking every 1000 assets. That is an honest reflection of
     * how the work now happens — the previous per-batch ticks measured in-heap record building,
     * which no longer exists.
     *
     * Task: T011, T060 (progress publishing)
     * Spec reference: FR-005, FR-007
     *
     * Note: NOT @Transactional - uses separate short transactions for each operation
     * to avoid holding database locks during long-running refresh process
     */
    open fun executeRefresh(job: MaterializedViewRefreshJob) {
        val thresholdDays = vulnerabilityConfigService.getReminderOneDays()
        log.info("Executing refresh with threshold: {} days", thresholdDays)

        // One timestamp for the whole rebuild, used for both the age arithmetic and
        // last_calculated_at, so every row in a snapshot is measured from the same instant.
        val now = LocalDateTime.now()
        val thresholdDate = now.minusDays(thresholdDays.toLong())

        // Step 1: size the job so the SSE progress stream and job history have a real denominator.
        // Cheap COUNT with the same predicates as the rebuild below.
        job.totalAssets = outdatedAssetRepository
            .countAssetsWithOverdueVulnerabilities(thresholdDate)
            .toInt()
        selfProvider.get().updateJob(job)
        log.info("Found {} assets with overdue non-excepted vulnerabilities", job.totalAssets)
        publishProgressEvent(job, "Rebuilding outdated-asset view...")

        // Step 2: Atomically replace the snapshot with one server-side statement. Readers (user
        // dashboard "Overdue Patching", Outdated Assets page) always see either the old complete
        // snapshot or the new one — never an empty or partial view. A crash before this point
        // leaves the previous snapshot intact.
        // Invoked via the self proxy - a direct call on `this` would bypass the AOP
        // interceptor and split the delete+insert into two separate transactions.
        val rowsWritten = selfProvider.get().swapMaterializedView(thresholdDate, now)

        // Step 3: Mark job as completed
        job.updateProgress(rowsWritten)
        job.markCompleted()
        selfProvider.get().updateJob(job)

        // Step 4 & 5: Refresh derived data (statistics cache + heatmap)
        refreshDerivedData()

        // Publish completion event
        publishProgressEvent(job,
            if (rowsWritten == 0) "Refresh completed: no outdated assets"
            else "Refresh completed successfully")

        log.info("Refresh completed: jobId={}, assetsProcessed={}, durationMs={}",
            job.id, job.assetsProcessed, job.durationMs)
    }

    /**
     * Replace the whole materialized view in one transaction, so concurrent readers never observe
     * a cleared-but-not-yet-rebuilt view (the cause of the dashboard's false "0 overdue assets").
     *
     * Both statements run entirely in the database — nothing is read into the JVM. This replaced a
     * `deleteAll()` + `saveAll(records)` pair fed by a list built in heap from ~166k managed
     * `Vulnerability` entities, which was the largest contributor to the 2026-07-30 import OOM.
     * See OutdatedAssetMaterializedViewRepository.rebuildFromOverdueVulnerabilities for how the
     * per-asset aggregation and the "oldest vulnerability" pick are expressed in SQL, and which
     * semantics are preserved exactly.
     *
     * @return number of rows written, i.e. assets now in the view
     */
    @Transactional
    open fun swapMaterializedView(thresholdDate: LocalDateTime, now: LocalDateTime): Int {
        outdatedAssetRepository.deleteAll()
        val rowsWritten = outdatedAssetRepository.rebuildFromOverdueVulnerabilities(thresholdDate, now)
        log.info("Materialized view rebuilt in-database: {} rows", rowsWritten)
        return rowsWritten
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
     * Takes no parameters on purpose. It used to receive executeRefresh()'s overdue-vulnerability
     * list so the AWS KPI step could reuse it — which pinned that whole ~166k-entity list in heap
     * for the duration of the statistics and heatmap work below. Every step here now issues its
     * own bounded query instead.
     */
    private fun refreshDerivedData() {
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
            awsCleanServerKpiService.recalculate()
        } catch (e: Exception) {
            log.error("AWS clean-server KPI recalculation failed (non-fatal): {}", e.message, e)
        }

        try {
            log.info("Recalculating EDR coverage KPI after materialized view refresh")
            edrCoverageKpiService.recalculate()
        } catch (e: Exception) {
            log.error("EDR coverage KPI recalculation failed (non-fatal): {}", e.message, e)
        }
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

        // Also emit to SSE stream for real-time updates. The result was previously discarded, which
        // is how a permanently-terminated sink went unnoticed — surface anything unexpected.
        // FAIL_ZERO_SUBSCRIBER is the normal case (no admin currently watching), not a problem.
        val emitResult = progressSink.tryEmitNext(event)
        if (emitResult != Sinks.EmitResult.OK && emitResult != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            log.warn("Progress event not delivered to SSE subscribers: jobId={}, result={}",
                job.id, emitResult)
        }

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

        // Quiet period: don't start while a bulk writer is still posting. Each
        // requestDeferredRefresh() pushes this deadline out, so a burst of import sub-batches
        // collapses into a single refresh that begins after the import goes quiet.
        val lastRequest = lastDeferredRequestAt.get()
        if (lastRequest != null) {
            val quietSeconds = ChronoUnit.SECONDS.between(lastRequest, LocalDateTime.now())
            if (quietSeconds < quietPeriodSeconds) {
                log.debug(
                    "Holding deferred refresh: {}s since last request, quiet period {}s",
                    quietSeconds, quietPeriodSeconds
                )
                return
            }
        }

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
