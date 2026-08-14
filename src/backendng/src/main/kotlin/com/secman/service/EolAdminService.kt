package com.secman.service

import com.secman.domain.EolProduct
import com.secman.domain.EolSyncRun
import com.secman.dto.EolSyncRequest
import com.secman.dto.EolSyncResponse
import com.secman.repository.EolSyncRunRepository
import io.micronaut.scheduling.annotation.Async
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Orchestrates the admin-triggered "download the catalogue, then re-match the
 * inventory" run and records it as an [EolSyncRun] audit row.
 *
 * Split from [EolCatalogSyncService] / [EolScanService] so those stay
 * independently testable and so the audit record is written exactly once per
 * user-visible action, with actor and outcome (§A09).
 *
 * ## Why this is asynchronous
 *
 * A full run is a download plus a full-inventory rescan and takes minutes
 * (measured: ~150s catalogue + ~50s scan against a 2,000-system estate). Held
 * open as a single HTTP request it exceeded the reverse proxy's read timeout —
 * Apache 2.4 and nginx both default to 60s — and the caller got a 504 while the
 * work carried on invisibly to completion. Raising the proxy timeout alone
 * would have made a multi-minute synchronous request load-bearing, so the run
 * is dispatched to a background thread and the [EolSyncRun] row doubles as the
 * job record clients poll. Same shape as [MaterializedViewRefreshService].
 *
 * The row is therefore written **twice**: once as RUNNING when the run is
 * accepted, and again with the outcome when the worker finishes.
 */
@Singleton
open class EolAdminService(
    private val catalogSyncService: EolCatalogSyncService,
    private val scanService: EolScanService,
    private val eolSyncRunRepository: EolSyncRunRepository
) {
    private val log = LoggerFactory.getLogger(EolAdminService::class.java)

    /**
     * Self-reference so `@Async` applies on the internal call. Calling
     * `this.executeSyncAsync(...)` would bypass the AOP proxy and run the work
     * on the request thread — reintroducing exactly the 504 this class exists
     * to avoid, silently and only in production. Field-injected rather than
     * constructor-injected to avoid a circular dependency at construction.
     */
    @Inject
    private lateinit var selfProvider: jakarta.inject.Provider<EolAdminService>

    companion object {
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_PARTIAL = "PARTIAL"
        const val STATUS_FAILED = "FAILED"

        /**
         * A RUNNING row older than this has lost its worker — process restart,
         * OOM, container replacement. Without reclaiming it the single-run
         * guard would wedge every future sync forever. Generous relative to the
         * ~4-minute real runtime so it can never fire on a live run.
         */
        val STALE_AFTER: Duration = Duration.ofHours(1)

        /** Matches the `products_failed` column width. */
        private const val PRODUCTS_FAILED_MAX = 2048
    }

    /**
     * Accept a sync, persist it as RUNNING, and hand the work to a background
     * thread. Returns immediately with the run's handle; the caller polls
     * [findRun] until [status] is terminal.
     *
     * At most one run is live at a time. That guard is not just tidiness: the
     * synchronous version was self-limiting because the caller had to wait out
     * the whole run before it could trigger another, and dispatching to a
     * background thread removes that. Without the guard an admin could put N
     * concurrent full-inventory rescans on the box in N requests (§A04).
     */
    open fun startSync(request: EolSyncRequest, triggeredBy: String): EolSyncResponse {
        val actor = sanitizeActor(triggeredBy)
        reclaimStaleRuns()

        alreadyRunning()?.let { live ->
            log.info(
                "EOL sync trigger by {} deferred to running run {} (started {})",
                actor, live.runId, live.startedAt
            )
            return toResponse(live)
        }

        val run = eolSyncRunRepository.save(
            EolSyncRun(
                runId = UUID.randomUUID().toString(),
                sourceKey = EolProduct.DEFAULT_SOURCE_KEY,
                triggeredBy = actor,
                status = STATUS_RUNNING,
                productsRequested = request.products.size,
                startedAt = Instant.now()
            )
        )

        // Re-check after the insert: the read above is not atomic with the save,
        // so two concurrent triggers can both pass it and both insert a RUNNING
        // row. Lowest id wins; the younger duplicate retires itself and defers
        // to the winner, leaving at most one live run.
        val oldest = alreadyRunning()
        if (oldest != null && oldest.id != run.id) {
            log.info(
                "Concurrent EOL sync trigger lost the race - deferring to run {} (retired {})",
                oldest.runId, run.runId
            )
            run.status = STATUS_FAILED
            run.errorSummary = "Duplicate trigger - EOL sync run ${oldest.runId} was already running"
            run.finishedAt = Instant.now()
            eolSyncRunRepository.update(run)
            return toResponse(oldest)
        }

        log.info(
            "EOL sync run {} accepted from {} (products={}, scan={}, scanOnly={})",
            run.runId, actor, request.products.size, request.scan, request.scanOnly
        )
        selfProvider.get().executeSyncAsync(run.runId, request)
        return toResponse(run)
    }

    /**
     * The actual work, on a background thread. Every exit path writes a
     * terminal status — a row left RUNNING would block subsequent syncs until
     * [STALE_AFTER] elapsed (§A09: no silently swallowed failure).
     */
    @Async
    open fun executeSyncAsync(runId: String, request: EolSyncRequest) {
        val run = eolSyncRunRepository.findByRunId(runId).orElse(null)
        if (run == null) {
            log.error("EOL sync run {} vanished before execution", runId)
            return
        }

        try {
            execute(run, request)
        } catch (e: Exception) {
            // Belt and braces: execute() already handles the failures it can
            // name. Anything reaching here is unexpected, and leaving the row
            // RUNNING would wedge the guard.
            log.error("EOL sync run {} failed unexpectedly (actor={})", runId, run.triggeredBy, e)
            run.status = STATUS_FAILED
            run.errorSummary = "EOL sync failed"
            run.finishedAt = Instant.now()
            eolSyncRunRepository.update(run)
        }
    }

    private fun execute(run: EolSyncRun, request: EolSyncRequest) {
        var catalogResult: EolCatalogSyncService.CatalogSyncResult? = null
        var scanResult: EolScanService.ScanResult? = null
        val errors = mutableListOf<String>()

        if (!request.scanOnly) {
            catalogResult = try {
                catalogSyncService.sync(request.products)
            } catch (e: Exception) {
                log.error("EOL catalogue sync failed (runId={}, actor={})", run.runId, run.triggeredBy, e)
                errors += "Catalogue download failed"
                null
            }
            catalogResult?.errorSummary?.let { errors += it }
        }

        // A scan against an empty catalogue is a no-op that reports why, so it is
        // still worth running after a failed download.
        if (request.scan || request.scanOnly) {
            scanResult = try {
                scanService.scan(request.horizonMonths)
            } catch (e: Exception) {
                log.error("EOL scan failed (runId={}, actor={})", run.runId, run.triggeredBy, e)
                errors += "Matching scan failed"
                null
            }
            scanResult?.errorSummary?.let { errors += it }
        }

        val status = when {
            errors.isEmpty() -> STATUS_SUCCESS
            catalogResult == null && scanResult == null -> STATUS_FAILED
            else -> STATUS_PARTIAL
        }

        run.status = status
        run.productsRequested = catalogResult?.productsRequested ?: run.productsRequested
        run.productsSynced = catalogResult?.productsSynced ?: 0
        run.releasesSynced = catalogResult?.releasesSynced ?: 0
        run.productsFailed = catalogResult?.productsFailed
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(",")
            ?.take(PRODUCTS_FAILED_MAX)
        run.assetsScanned = scanResult?.assetsScanned ?: 0
        run.repositoriesScanned = scanResult?.repositoriesScanned ?: 0
        run.findingsWritten = scanResult?.findingsWritten ?: 0
        run.findingsRemoved = scanResult?.findingsRemoved ?: 0
        run.eolFindings = scanResult?.eolFindings ?: 0
        run.approachingFindings = scanResult?.approachingFindings ?: 0
        run.errorSummary = errors.joinToString("; ").takeIf { it.isNotEmpty() }?.take(1024)
        run.finishedAt = Instant.now()
        eolSyncRunRepository.update(run)

        log.info(
            "EOL sync run {} by {}: status={} products={} releases={} findings={} removed={}",
            run.runId, run.triggeredBy, status, run.productsSynced, run.releasesSynced,
            run.findingsWritten, run.findingsRemoved
        )
    }

    /**
     * Current state of one run, for polling. Returns null for an unknown
     * handle — the caller turns that into a generic 404 (§A05).
     */
    open fun findRun(runId: String): EolSyncResponse? =
        eolSyncRunRepository.findByRunId(runId).map { toResponse(it) }.orElse(null)

    private fun alreadyRunning(): EolSyncRun? =
        eolSyncRunRepository.findByStatusOrderByIdAsc(STATUS_RUNNING).firstOrNull()

    /**
     * Retire RUNNING rows whose worker is demonstrably gone, so a crash mid-run
     * does not disable EOL syncing permanently.
     */
    private fun reclaimStaleRuns() {
        val cutoff = Instant.now().minus(STALE_AFTER)
        eolSyncRunRepository.findByStatusOrderByIdAsc(STATUS_RUNNING)
            .filter { it.startedAt.isBefore(cutoff) }
            .forEach { stale ->
                log.warn(
                    "Reclaiming stale EOL sync run {} (started {}, exceeded {})",
                    stale.runId, stale.startedAt, STALE_AFTER
                )
                stale.status = STATUS_FAILED
                stale.errorSummary =
                    "Run exceeded ${STALE_AFTER.toHours()}h and was reclaimed (likely orphaned by a process restart)"
                stale.finishedAt = Instant.now()
                eolSyncRunRepository.update(stale)
            }
    }

    private fun toResponse(run: EolSyncRun): EolSyncResponse = EolSyncResponse(
        runId = run.runId,
        status = run.status,
        productsRequested = run.productsRequested,
        productsSynced = run.productsSynced,
        releasesSynced = run.releasesSynced,
        productsFailed = run.productsFailed?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
        assetsScanned = run.assetsScanned,
        repositoriesScanned = run.repositoriesScanned,
        findingsWritten = run.findingsWritten,
        eolFindings = run.eolFindings,
        approachingFindings = run.approachingFindings,
        findingsRemoved = run.findingsRemoved,
        errorSummary = run.errorSummary
    )

    /** The actor lands in a stored audit row and a log line — strip CR/LF (§A09). */
    private fun sanitizeActor(raw: String): String =
        raw.replace(Regex("[\\r\\n\\t]"), "_").trim().take(255).ifEmpty { "unknown" }
}
