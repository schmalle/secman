package com.secman.service

import com.secman.domain.VulnerabilityException.Scope
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.scheduling.annotation.Scheduled
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
                val updated = vulnerabilityRepository.recomputeExceptedForAssets(ids)
                log.debug("excepted recompute after create: scope={} assets={} rows={}", scope, ids.size, updated)
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
                val updated = vulnerabilityRepository.recomputeExceptedForAssets(ids)
                log.debug("excepted recompute after delete: scope={} assets={} rows={}", scope, ids.size, updated)
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
     * so this is cheap. (Full drift correction is the post-import [recomputeAllExcepted] safety net.)
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
     * Recompute `excepted` for a single asset's rows — called by importers after they replace an
     * asset's vulnerability set (transactional delete+insert). Bounded and fast (rides
     * idx_vulnerability_asset_scan). Caller must have flushed the new rows to the DB first, since the
     * recompute is native SQL that reads the table directly.
     */
    @Transactional
    open fun recomputeForAsset(assetId: Long): Long =
        vulnerabilityRepository.recomputeExceptedForAssets(listOf(assetId))

    /**
     * Full-table recompute — the drift safety net invoked from the post-import refresh lifecycle
     * (off the request path). Synchronous here because the caller already runs async and expects to
     * hold a connection for the batch window. ~124s-class.
     */
    @Transactional
    open fun recomputeAllExcepted(): Long = vulnerabilityRepository.recomputeExceptedAll()

    /**
     * Resolve the assets an exception scope covers, mirroring ExceptionMatchSql.EXCEPTION_MATCH.
     * Returns null for GLOBAL (no asset bound — caller chooses full vs currently-excepted recompute);
     * an empty list means the scope currently matches no assets (nothing to recompute).
     */
    private fun affectedAssetIds(scope: Scope, scopeValue: String?, assetId: Long?): List<Long>? = when (scope) {
        Scope.GLOBAL -> null
        Scope.ASSET -> assetId?.let { listOf(it) } ?: emptyList()
        Scope.IP -> scopeValue?.let { v -> assetRepository.findByIp(v).mapNotNull { it.id } } ?: emptyList()
        Scope.AWS_ACCOUNT -> scopeValue?.let { v -> assetRepository.findByCloudAccountId(v).mapNotNull { it.id } } ?: emptyList()
        Scope.OS -> scopeValue?.let { v -> assetRepository.findByOsVersionContainingIgnoreCase(v).mapNotNull { it.id } } ?: emptyList()
    }
}
