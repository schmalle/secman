package com.secman.relay

import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Pushes the status snapshot to the relay on a timer.
 *
 * The publisher is the only thing in secman that initiates an outbound
 * connection to the relay, and it is a one-way street: it sends, reads the
 * acknowledgement, and forgets. Nothing the relay returns is acted on beyond
 * being recorded for the admin status view — a compromised relay can therefore
 * lie about having received a snapshot, and that is the entire extent of what
 * it can do to secman.
 *
 * Failures never propagate. A relay that is down, unreachable or refusing
 * pushes must not affect anything else in secman, so every error is caught,
 * counted and logged.
 */
@Singleton
open class RelayPublisher(
    private val properties: RelayProperties,
    private val snapshotBuilder: RelaySnapshotBuilder,
    private val client: RelayClient
) {
    private val logger = LoggerFactory.getLogger(RelayPublisher::class.java)

    private val lastAttemptAt = AtomicReference<Instant?>(null)
    private val lastSuccessAt = AtomicReference<Instant?>(null)
    private val lastError = AtomicReference<String?>(null)
    private val consecutiveFailures = AtomicInteger(0)
    private val attempted = AtomicLong(0)
    private val succeeded = AtomicLong(0)

    /**
     * The scheduled push.
     *
     * `fixedDelay` measures from the end of one run to the start of the next, so
     * a slow or timing-out relay stretches the interval instead of piling
     * overlapping pushes onto the scheduler pool.
     */
    @Scheduled(fixedDelay = "\${secman.relay.publish-interval:60s}", initialDelay = "30s")
    open fun publishScheduled() {
        if (!properties.enabled) return
        publish(trigger = "scheduled")
    }

    /**
     * Pushes now. Used by the scheduler and by the admin "publish now" endpoint.
     *
     * @return null on success, otherwise a message safe to show an admin.
     */
    open fun publish(trigger: String): String? {
        if (!properties.enabled) {
            return "Relay publishing is disabled (set SECMAN_RELAY_ENABLED=true)"
        }
        lastAttemptAt.set(Instant.now())
        attempted.incrementAndGet()

        val snapshot = try {
            snapshotBuilder.build(properties.instanceId, properties.sections)
        } catch (e: Exception) {
            return recordFailure("Building the snapshot failed: ${sanitizeForLog(e.message ?: e.javaClass.simpleName)}")
        }

        val result = client.pushSnapshot(snapshot)
        if (!result.success) {
            return recordFailure(result.error ?: "unknown relay error")
        }

        succeeded.incrementAndGet()
        lastSuccessAt.set(Instant.now())
        lastError.set(null)
        val previousFailures = consecutiveFailures.getAndSet(0)

        // Actor + target + outcome (A09). Logged at info on recovery and at
        // debug otherwise, so a healthy relay does not produce a log line a
        // minute forever while an outage recovery is still visible.
        if (previousFailures > 0) {
            logger.info(
                "Relay push recovered after {} failures: trigger={} sections={} instanceId={}",
                previousFailures, trigger, snapshot.sections.keys, properties.instanceId
            )
        } else {
            logger.debug(
                "Relay push accepted: trigger={} sections={} instanceId={}",
                trigger, snapshot.sections.keys, properties.instanceId
            )
        }
        return null
    }

    /**
     * Pushes a control document. Separate from the snapshot so that issuing an
     * enrollment code or a revocation takes effect immediately rather than at
     * the next scheduled tick.
     */
    open fun publishControl(control: RelayControl): String? {
        if (!properties.enabled) {
            return "Relay publishing is disabled (set SECMAN_RELAY_ENABLED=true)"
        }
        val result = client.pushControl(control)
        if (!result.success) {
            val message = result.error ?: "unknown relay error"
            logger.warn("Relay control push failed: {}", sanitizeForLog(message))
            return message
        }
        logger.info(
            "Relay control push accepted: enrollments={} revocations={} instanceId={}",
            control.enrollments.size, control.revocations.size, properties.instanceId
        )
        return null
    }

    /** Local publisher state for the admin status endpoint. */
    open fun status(): RelayStatusResponse = RelayStatusResponse(
        enabled = properties.enabled,
        url = properties.url.ifBlank { null },
        instanceId = properties.instanceId,
        sections = properties.sections,
        lastAttemptAt = lastAttemptAt.get()?.let { RelaySnapshotBuilder.rfc3339(it) },
        lastSuccessAt = lastSuccessAt.get()?.let { RelaySnapshotBuilder.rfc3339(it) },
        lastError = lastError.get(),
        consecutiveFailures = consecutiveFailures.get(),
        pushesAttempted = attempted.get(),
        pushesSucceeded = succeeded.get()
    )

    private fun recordFailure(message: String): String {
        val clean = sanitizeForLog(message)
        lastError.set(clean)
        val failures = consecutiveFailures.incrementAndGet()
        // Noisy for the first few, then quiet: a relay that has been down for a
        // day should not dominate the log, but the outage must still be visible.
        if (failures <= 3 || failures % 30 == 0) {
            logger.warn("Relay push failed ({} consecutive): {}", failures, clean)
        }
        return clean
    }
}
