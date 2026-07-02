package com.secman.service

import com.secman.repository.VulnerabilityRepository
import io.micronaut.scheduling.annotation.Async
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
     * Full-table recompute on a pooled async thread, in its own transaction (executeUpdate needs one).
     * Long-running by design and NOT statement-time-capped — must never run on a request path.
     */
    @Async
    @Transactional
    open fun recomputeAll() {
        val start = System.currentTimeMillis()
        try {
            val updated = vulnerabilityRepository.recomputeExceptedAll()
            log.info("Async full excepted recompute complete: {} rows in {} ms", updated, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            log.error("Async full excepted recompute failed (non-fatal; standing sweeps will retry): {}", e.message, e)
        }
    }
}
