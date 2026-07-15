package com.secman.service

import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.ThreadLocalRandom

/**
 * Shared MariaDB/InnoDB deadlock detection + jittered-backoff retry, lifted from the proven
 * pattern in [CrowdStrikeVulnerabilityImportService]. Callers must pass a lambda that invokes a
 * fresh `@Transactional` method through an injected `Provider`/AOP proxy so each retry attempt
 * starts a new transaction rather than continuing one InnoDB just rolled back.
 */
object DeadlockRetry {
    private val log = LoggerFactory.getLogger(DeadlockRetry::class.java)

    /**
     * Returns true if [e] (or any cause in its chain) is a MariaDB/InnoDB deadlock or lock-wait
     * timeout.
     */
    fun isDeadlockException(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val name = cur::class.java.name
            if (name.endsWith("LockAcquisitionException") ||
                name.endsWith("CannotAcquireLockException") ||
                name.endsWith("PessimisticLockException")
            ) return true
            if (cur is SQLException) {
                // 1213 = deadlock found; 1205 = lock-wait timeout; SQLState 40001 = serialization failure
                if (cur.errorCode == 1213 || cur.errorCode == 1205 || cur.sqlState == "40001") return true
            }
            cur = cur.cause
        }
        return false
    }

    /**
     * Run [block] inside a deadlock-aware retry. Backoff is exponential with full jitter
     * (AWS-style): each attempt waits a random duration in [base/2, base*1.5] where base doubles
     * per attempt. Up to 5 attempts total (4 retries).
     */
    fun <T> withRetry(label: String, block: () -> T): T {
        val maxAttempts = 4 // → 5 attempts total including the initial try
        val baseMs = 100L
        val rng = ThreadLocalRandom.current()
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (!isDeadlockException(e) || attempt >= maxAttempts) throw e
                val expBase = baseMs shl attempt // 100, 200, 400, 800
                val sleepMs = (expBase * (0.5 + rng.nextDouble())).toLong().coerceAtLeast(1L)
                log.warn(
                    "Deadlock retry {}/{} for {} after {} ms: {}",
                    attempt + 1, maxAttempts, label, sleepMs, e.message
                )
                Thread.sleep(sleepMs)
                attempt++
            }
        }
    }
}
