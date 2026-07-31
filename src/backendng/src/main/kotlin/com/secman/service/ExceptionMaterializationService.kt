package com.secman.service

import com.secman.domain.VulnerabilityException.Scope
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Inject
import jakarta.inject.Provider
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/**
 * Keeps the materialized `vulnerability.excepted` flag in sync with the active-exception set so the
 * hot "current vulnerabilities" query can filter with a sargable `WHERE excepted = 0` instead of the
 * old ~124s correlated NOT EXISTS(EXCEPTION_MATCH).
 *
 * Single owner of the write-time recompute policy. Callers describe WHAT changed (an exception was
 * created / updated / deleted, with its scope); this service decides the cheapest correct recompute.
 * Every path re-derives from the SAME predicate the reads use (ExceptionMatchSql.EXCEPTION_MATCH,
 * interpolated into the VulnerabilityRepository.recompute* queries), so "matches" has one definition.
 *
 * Safety invariant (the only dangerous direction is wrongly HIDING a live vulnerability):
 *   - widen (create): bounded scopes recompute their assets synchronously; GLOBAL dispatches an async
 *     full recompute — during that window newly-excepted rows merely stay visible (safe).
 *   - narrow (delete): bounded scopes recompute their assets synchronously; GLOBAL runs the cheap
 *     currently-excepted sweep synchronously — un-hiding MUST be immediate and is cheap (only the
 *     small suppressed set is revisited).
 *   - update = narrow(old scope) then widen(new scope).
 *   - expiry (no CRUD event) is covered by the hourly [sweepExpiredExceptions].
 *   - startup convergence + a full-recompute safety net cover any residual drift.
 */
@Singleton
open class ExceptionMaterializationService(
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val assetRepository: AssetRepository,
    private val asyncExceptionRecompute: AsyncExceptionRecompute,
) {
    private val log = LoggerFactory.getLogger(ExceptionMaterializationService::class.java)

    /**
     * Provider for self-reference so [DeadlockRetry] in [recomputeAllExceptedScheduled] gets a
     * fresh `@Transactional` transaction per attempt, via the AOP proxy.
     */
    @Inject
    private lateinit var selfProvider: Provider<ExceptionMaterializationService>

    companion object {
        /** Asset ids per recompute statement. See [recomputeChunked]. */
        private const val RECOMPUTE_CHUNK_SIZE = 1000
    }

    /** An exception's coverage WIDENED (create, or the new side of an update). May hide more rows. */
    @Transactional
    open fun onExceptionCreated(scope: Scope, scopeValue: String?, assetId: Long?) {
        val ids = affectedAssetIds(scope, scopeValue, assetId)
        when {
            ids == null -> {
                log.info("GLOBAL-scope exception created -> async full excepted recompute (hide pass)")
                asyncExceptionRecompute.recomputeAll()
            }
            ids.isNotEmpty() -> {
                val updated = recomputeChunked(ids)
                log.info("excepted recompute after create: scope={} assets={} rows={}", scope, ids.size, updated)
            }
            else -> log.debug("excepted recompute after create: scope={} matched no assets", scope)
        }
    }

    /** An exception's coverage NARROWED (delete, or the old side of an update). May un-hide rows. */
    @Transactional
    open fun onExceptionDeleted(scope: Scope, scopeValue: String?, assetId: Long?) {
        val ids = affectedAssetIds(scope, scopeValue, assetId)
        when {
            ids == null -> {
                // GLOBAL un-hide: only currently-suppressed rows can flip back -> cheap, and must be
                // synchronous so a revoked exception stops hiding rows immediately.
                val updated = vulnerabilityRepository.recomputeExceptedForCurrentlyExcepted()
                log.info("GLOBAL-scope exception deleted -> currently-excepted un-hide sweep: rows={}", updated)
            }
            ids.isNotEmpty() -> {
                val updated = recomputeChunked(ids)
                log.info("excepted recompute after delete: scope={} assets={} rows={}", scope, ids.size, updated)
            }
            else -> log.debug("excepted recompute after delete: scope={} matched no assets", scope)
        }
    }

    /**
     * An exception's identification changed. Recompute the UNION of the old and new scopes: narrowing
     * the old scope un-hides rows the exception no longer covers (the dangerous direction if skipped),
     * then widening the new scope hides the rows it now covers.
     */
    @Transactional
    open fun onExceptionUpdated(
        oldScope: Scope, oldScopeValue: String?, oldAssetId: Long?,
        newScope: Scope, newScopeValue: String?, newAssetId: Long?,
    ) {
        onExceptionDeleted(oldScope, oldScopeValue, oldAssetId)
        onExceptionCreated(newScope, newScopeValue, newAssetId)
    }

    /**
     * Hourly safety net for exception EXPIRY, which fires no CRUD event: an expired exception stops
     * matching, so its rows must flip back to visible. Only currently-suppressed rows can be affected,
     * so this is cheap. (Full drift correction is the daily [recomputeAllExceptedScheduled] safety net.)
     */
    @Scheduled(fixedDelay = "1h", initialDelay = "15m")
    @Transactional
    open fun sweepExpiredExceptions() {
        try {
            val updated = vulnerabilityRepository.recomputeExceptedForCurrentlyExcepted()
            if (updated > 0) log.info("Expiry sweep recomputed {} currently-excepted rows", updated)
        } catch (e: Exception) {
            log.error("Expiry sweep failed (non-fatal): {}", e.message, e)
        }
    }

    /**
     * Daily full-table recompute of `excepted` — the drift safety net for edge cases the
     * incremental per-CRUD and hourly expiry paths miss (e.g. a manual asset metadata edit
     * that changed IP/OS/AWS-scope coverage). Previously invoked synchronously from every
     * materialized-view refresh (~124s-class, on every manual click and CrowdStrike import);
     * moved to its own off-peak schedule since drift here is rare, not something the fast,
     * latency-sensitive refresh path needs to re-verify on every run. Calls the repository
     * directly rather than through a same-class wrapper — Micronaut's compile-time-AOP
     * `@Transactional` interceptor is not guaranteed to apply to same-class self-invocation.
     */
    @Scheduled(cron = "0 0 3 * * ?")
    open fun recomputeAllExceptedScheduled() {
        try {
            val updated = DeadlockRetry.withRetry("daily full excepted recompute") {
                selfProvider.get().recomputeAllExceptedOnce()
            }
            if (updated > 0) log.info("Scheduled full excepted-flag recompute updated {} rows", updated)
        } catch (e: Exception) {
            log.error("Scheduled full excepted-flag recompute failed (non-fatal): {}", e.message, e)
        }
    }

    // REQUIRES_NEW for the same reason as AsyncExceptionRecompute.recomputeAllOnce(): must always
    // be a genuinely fresh, independent transaction per DeadlockRetry attempt, never joined to any
    // ambient caller transaction (default REQUIRED propagation would do that).
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun recomputeAllExceptedOnce(): Long = vulnerabilityRepository.recomputeExceptedAll()

    /**
     * Recompute `excepted` for a single asset's rows — called by importers after they replace an
     * asset's vulnerability set (transactional delete+insert). Bounded and fast (rides
     * idx_vulnerability_asset_scan). Caller must have flushed the new rows to the DB first, since the
     * recompute is native SQL that reads the table directly.
     */
    @Transactional
    open fun recomputeForAsset(assetId: Long): Long =
        vulnerabilityRepository.recomputeExceptedForAssets(listOf(assetId))

    /**
     * Resolve the assets an exception scope covers, mirroring ExceptionMatchSql.EXCEPTION_MATCH.
     * Returns null for GLOBAL (no asset bound — caller chooses full vs currently-excepted recompute);
     * an empty list means the scope currently matches no assets (nothing to recompute).
     */
    /**
     * Run the scope-bounded recompute in chunks of [RECOMPUTE_CHUNK_SIZE] asset ids.
     *
     * A single AWS_ACCOUNT or OS scope can resolve to thousands of assets, and the underlying
     * statement inlines the whole list into `WHERE asset_id IN (...)` (useServerPrepStmts is off).
     * Unchunked that risks an oversized statement and holds write locks on the vulnerability table
     * for the duration of one very large UPDATE — contention the surrounding DeadlockRetry then has
     * to absorb. 1000 matches the chunk size used by CrowdStrikeVulnerabilityImportService's
     * reconcile sweep and AssetRepository.findWorkgroupIdsByAssetIds callers.
     */
    private fun recomputeChunked(ids: List<Long>): Long =
        ids.chunked(RECOMPUTE_CHUNK_SIZE)
            .sumOf { vulnerabilityRepository.recomputeExceptedForAssets(it) }

    private fun affectedAssetIds(scope: Scope, scopeValue: String?, assetId: Long?): List<Long>? = when (scope) {
        Scope.GLOBAL -> null
        Scope.ASSET -> assetId?.let { listOf(it) } ?: emptyList()
        Scope.IP -> scopeValue?.let { v -> assetRepository.findByIp(v).mapNotNull { it.id } } ?: emptyList()
        Scope.AWS_ACCOUNT -> scopeValue?.let { v -> assetRepository.findByCloudAccountId(v).mapNotNull { it.id } } ?: emptyList()
        Scope.OS -> scopeValue?.let { v -> assetRepository.findByOsVersionContainingIgnoreCase(v).mapNotNull { it.id } } ?: emptyList()
    }
}
