package com.secman.service

import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixed-window rate limiting for the account-onboarding surfaces.
 *
 * SecMan has no general-purpose limiter — the only existing limits are per-MCP-tool — and this
 * feature adds the first genuinely unauthenticated write path in the codebase
 * ([com.secman.controller.AccountOnboardingPublicController]). Without a limit, that path is:
 *
 * - a token oracle, brute-forceable at whatever rate the network allows, and
 * - an amplifier: each accepted submission creates a risk assessment and sends a mail.
 *
 * 256-bit tokens make guessing hopeless on arithmetic alone; the limiter is what makes it
 * *cheap* to be hopeless, and it caps the damage a single leaked token can do.
 *
 * ### Deliberately in-memory
 *
 * Per-instance, not shared. On a multi-instance deployment the effective limit is N times the
 * configured one, which is the accepted trade: a DB-backed counter would put a write on every
 * unauthenticated request — itself a denial-of-service lever — and SecMan runs single-instance
 * today. Documented rather than silently assumed.
 *
 * The map is capped ([MAX_TRACKED_KEYS]) so a caller cycling source addresses cannot grow it
 * without bound; when the cap is hit the window is cleared wholesale. Losing counters is a
 * lesser failure than exhausting the heap, and the window is short.
 */
@Singleton
open class AccountOnboardingRateLimiter {

    private val log = LoggerFactory.getLogger(AccountOnboardingRateLimiter::class.java)

    companion object {
        /** Reading the questionnaire form. Generous — a slow connection may retry. */
        const val GET_LIMIT = 20
        const val GET_WINDOW_SECONDS = 600L

        /** Submitting. Each accepted one creates an assessment, so this is tight. */
        const val POST_LIMIT = 5
        const val POST_WINDOW_SECONDS = 600L

        /**
         * Failed token lookups per source, over a longer window.
         *
         * The one that actually blunts enumeration: a caller probing tokens hits only this
         * bucket, because none of their probes ever succeeds.
         */
        const val FAILED_LOOKUP_LIMIT = 10
        const val FAILED_LOOKUP_WINDOW_SECONDS = 3600L

        /** Live simulations per actor per hour. Dry runs use [SIMULATE_DRY_LIMIT]. */
        const val SIMULATE_LIMIT = 20
        const val SIMULATE_WINDOW_SECONDS = 3600L
        const val SIMULATE_DRY_LIMIT = 200

        /** Upper bound on distinct keys held at once, across all buckets. */
        const val MAX_TRACKED_KEYS = 10_000
    }

    /** Which bucket a request is counted against. Buckets never share a counter. */
    enum class Bucket(val limit: Int, val windowSeconds: Long) {
        PUBLIC_GET(GET_LIMIT, GET_WINDOW_SECONDS),
        PUBLIC_POST(POST_LIMIT, POST_WINDOW_SECONDS),
        FAILED_LOOKUP(FAILED_LOOKUP_LIMIT, FAILED_LOOKUP_WINDOW_SECONDS),
        SIMULATE(SIMULATE_LIMIT, SIMULATE_WINDOW_SECONDS),
        SIMULATE_DRY(SIMULATE_DRY_LIMIT, SIMULATE_WINDOW_SECONDS)
    }

    private data class Counter(val windowStart: Long, val count: AtomicInteger)

    private val counters = ConcurrentHashMap<String, Counter>()

    /**
     * Count one request and report whether it is within the limit.
     *
     * @param key what to count against — a client address for the public buckets, an actor id
     *        for the simulate buckets. Never a token: keying on the credential would make the
     *        limiter itself a way to test whether a token exists.
     * @return true when the request may proceed, false when it must be answered 429.
     */
    open fun tryAcquire(bucket: Bucket, key: String): Boolean {
        if (counters.size >= MAX_TRACKED_KEYS) {
            // Bounded rather than unbounded. A dropped window is a smaller problem than a heap
            // an attacker controls the size of.
            log.warn("Rate limiter tracking {} keys - clearing (windows are short)", counters.size)
            counters.clear()
        }

        val now = Instant.now().epochSecond
        val window = now - (now % bucket.windowSeconds)
        val mapKey = "${bucket.name}:$key"

        val counter = counters.compute(mapKey) { _, existing ->
            if (existing == null || existing.windowStart != window) Counter(window, AtomicInteger(0))
            else existing
        }!!

        val used = counter.count.incrementAndGet()
        if (used > bucket.limit) {
            log.warn(
                "Rate limit exceeded: bucket={}, key={}, used={}, limit={}",
                bucket.name, redactKey(key), used, bucket.limit
            )
            return false
        }
        return true
    }

    /** Seconds until the caller's current window rolls over. Fed to `Retry-After`. */
    open fun retryAfterSeconds(bucket: Bucket): Long {
        val now = Instant.now().epochSecond
        return bucket.windowSeconds - (now % bucket.windowSeconds)
    }

    /** Only for tests and for a deliberate operator reset. */
    open fun reset() = counters.clear()

    /**
     * A client address is personal data and ends up in a log line. Keep the network prefix,
     * which is what makes the entry useful, and drop the host part.
     */
    private fun redactKey(key: String): String =
        when {
            key.count { it == '.' } == 3 -> key.substringBeforeLast('.') + ".x"
            key.contains(':') -> key.substringBeforeLast(':') + ":x"
            else -> key.take(12)
        }
}
