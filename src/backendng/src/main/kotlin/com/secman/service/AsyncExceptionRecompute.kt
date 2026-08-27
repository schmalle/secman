package com.secman.service

import com.secman.repository.VulnerabilityRepository
import io.micronaut.data.model.Pageable
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
 *   - GLOBAL-scope exception creation (hide-direction over the whole table), where the safe
 *     direction is "rows stay visible until it lands", so the request must not wait;
 *   - one-shot convergence on application startup (the V236 migration intentionally skips the
 *     inline backfill to keep boot under the liveness budget);
 *   - the daily drift safety net, via [ExceptionMaterializationService.recomputeAllExceptedScheduled].
 *
 * ## Why this is chunked
 *
 * It used to be one `UPDATE` with no `WHERE`. That takes exclusive row locks on every
 * `vulnerability` row (~1.8M in production) and, through its correlated `EXISTS` subquery, read
 * locks on `vulnerability_exception` — holding both for the entire 53–180s transaction. Every
 * concurrent writer to either table then blocked until lock-wait-timeout: bulk exception
 * delete/import, CrowdStrike imports, per-asset recomputes. [DeadlockRetry] made that survivable,
 * which hid it, but the *other* operation still paid the full timeout first (~40s per observed
 * `delete_all_vulnerability_exceptions` run).
 *
 * Chunking does not make the work cheaper — the same rows are still evaluated — it makes the
 * **lock hold** short. Each chunk is an independent transaction covering a bounded id range, so
 * competing writers interleave between chunks instead of queueing behind one multi-minute lock set.
 *
 * ## Why giving up atomicity is safe here
 *
 * This is the part that has to be argued rather than assumed. A chunked run is not atomic: a reader
 * mid-run sees some rows recomputed and some not. That costs nothing, because no guarantee existed
 * to lose:
 *   - the operation is already `@Async`, so callers never observed it synchronously anyway;
 *   - the documented contract is already eventual ("rows stay visible until it lands", failures are
 *     "non-fatal; standing sweeps will retry");
 *   - a mid-statement failure already left partial state — the single UPDATE's rollback was
 *     immediately followed by a swallow-and-log, not by a compensating action;
 *   - readers evaluate `v.excepted = 0` per row and never take a cross-table snapshot.
 *
 * What chunking *does* change is that a failure now leaves a **prefix** converged rather than
 * nothing. That is strictly better: partial progress in the safe direction, and the next scheduled
 * sweep finishes the job.
 */
@Singleton
open class AsyncExceptionRecompute(
    private val vulnerabilityRepository: VulnerabilityRepository,
) {
    private val log = LoggerFactory.getLogger(AsyncExceptionRecompute::class.java)

    /**
     * Provider for self-reference so each chunk below goes through the AOP proxy and gets a fresh
     * `@Transactional` transaction, never a continuation of one InnoDB just rolled back for a
     * deadlock.
     */
    @Inject
    private lateinit var selfProvider: Provider<AsyncExceptionRecompute>

    /**
     * Full recompute on a pooled async thread. Long-running by design and NOT statement-time-capped
     * — must never run on a request path.
     */
    @Async
    open fun recomputeAll() {
        val start = System.currentTimeMillis()
        try {
            val updated = recomputeAllChunked("async full excepted recompute")
            log.info(
                "Async full excepted recompute complete: {} rows in {} ms",
                updated, System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            log.error("Async full excepted recompute failed (non-fatal; standing sweeps will retry): {}", e.message, e)
        }
    }

    /**
     * Drives the recompute as bounded keyset chunks, each in its own short transaction.
     *
     * Retry is per chunk rather than around the whole run: a chunk that loses a deadlock race
     * replays only its own bounded range, and chunks that already landed are not redone.
     *
     * @param reason label for log lines and retry diagnostics.
     * @return total rows updated across all chunks.
     */
    open fun recomputeAllChunked(reason: String): Long {
        var afterId = 0L
        var total = 0L
        var chunks = 0

        while (chunks < MAX_CHUNKS) {
            // Keyset, not fixed-width id stepping: the CrowdStrike import replaces rows per asset
            // (delete-then-insert), so ids are sparse and MAX(id) runs far ahead of the row count.
            val boundary = vulnerabilityRepository
                .findIdsAfter(afterId, Pageable.from(0, CHUNK_SIZE))
                .lastOrNull() ?: break

            total += DeadlockRetry.withRetry("$reason [id ${afterId + 1}..$boundary]") {
                selfProvider.get().recomputeExceptedChunk(afterId, boundary)
            }
            afterId = boundary
            chunks++
        }

        if (chunks >= MAX_CHUNKS) {
            // A runaway backstop, not an expected outcome: at CHUNK_SIZE rows per chunk this is
            // orders of magnitude past any real table. Reaching it means the keyset stopped
            // advancing, so say so rather than silently reporting a partial run as complete.
            log.error(
                "{}: hit the {} chunk backstop at id {} — recompute is INCOMPLETE",
                reason, MAX_CHUNKS, afterId
            )
        }
        return total
    }

    /**
     * One bounded chunk.
     *
     * REQUIRES_NEW because callers may dispatch us from inside their own active `@Transactional`
     * (e.g. [ExceptionMaterializationService.onExceptionCreated]'s GLOBAL branch). Micronaut's
     * default REQUIRED propagation would join that still-open transaction, which would both run
     * this on the caller's connection and — fatally for the whole point of chunking — keep every
     * chunk's locks held until the *caller's* transaction committed.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun recomputeExceptedChunk(afterId: Long, throughId: Long): Long =
        vulnerabilityRepository.recomputeExceptedForIdRange(afterId, throughId)

    companion object {
        /**
         * Rows per chunk. Small enough that a chunk's lock set is held for milliseconds, large
         * enough that per-transaction overhead stays a rounding error against the per-row cost of
         * evaluating the exception-match subquery.
         */
        const val CHUNK_SIZE = 10_000

        /** Runaway guard; far above any real table at [CHUNK_SIZE] rows per chunk. */
        const val MAX_CHUNKS = 100_000
    }
}
