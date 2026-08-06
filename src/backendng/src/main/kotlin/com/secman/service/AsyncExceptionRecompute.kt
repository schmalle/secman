package com.secman.service

import com.secman.repository.VulnerabilityRepository
import io.micronaut.scheduling.annotation.Async
import jakarta.inject.Inject
import jakarta.inject.Provider
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/**
 * Off-request-thread full recompute of `vulnerability.excepted`.
 *
 * Deliberately a SEPARATE bean from [ExceptionMaterializationService]: Micronaut applies `@Async`
 * advice only on cross-bean calls, so the orchestrator invoking us by injected reference guarantees
 * the recompute actually runs asynchronously instead of blocking the caller's thread. Used for:
 *   - GLOBAL-scope exception creation (hide-direction over the whole table, ~124s), where the safe
 *     direction is "rows stay visible until it lands", so the request must not wait;
 *   - one-shot convergence on application startup (the V236 migration intentionally skips the ~124s
 *     inline backfill to keep boot under the liveness budget).
 */
@Singleton
open class AsyncExceptionRecompute(
    private val vulnerabilityRepository: VulnerabilityRepository,
) {
    private val log = LoggerFactory.getLogger(AsyncExceptionRecompute::class.java)

    /**
     * Provider for self-reference so each [DeadlockRetry] attempt below goes through the AOP
     * proxy and gets a fresh `@Transactional` transaction, never a continuation of one InnoDB
     * just rolled back for a deadlock.
     */
    @Inject
    private lateinit var selfProvider: Provider<AsyncExceptionRecompute>

    /**
     * Full-table recompute on a pooled async thread. Long-running by design and NOT
     * statement-time-capped — must never run on a request path. The statement touches nearly the
     * whole `vulnerability` table for ~124s, so it routinely collides with other concurrent
     * writers (bounded per-asset recomputes, the daily full-recompute twin, CrowdStrike imports);
     * [DeadlockRetry] absorbs the resulting transient InnoDB deadlocks.
     */
    @Async
    open fun recomputeAll() {
        val start = System.currentTimeMillis()
        try {
            val updated = DeadlockRetry.withRetry("full excepted recompute") {
                selfProvider.get().recomputeAllOnce()
            }
            log.info("Async full excepted recompute complete: {} rows in {} ms", updated, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            log.error("Async full excepted recompute failed (non-fatal; standing sweeps will retry): {}", e.message, e)
        }
    }

    // REQUIRES_NEW: the caller (ExceptionMaterializationService.onExceptionCreated, GLOBAL branch)
    // dispatches us from inside its OWN active @Transactional. Micronaut's default REQUIRED
    // propagation would join that still-open transaction/connection instead of opening an
    // independent one, so this ~124s statement would run ON the caller's connection and block
    // its commit (and therefore the HTTP request) until the recompute finishes. See
    // AiSuggestionJobService / ExportJobService for the same REQUIRES_NEW pattern.
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun recomputeAllOnce(): Long = vulnerabilityRepository.recomputeExceptedAll()
}
