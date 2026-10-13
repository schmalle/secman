package com.secman.cli.service

import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/** Keeps the backend run alive while the producer waits on Falcon pagination. */
class CrowdStrikeImportSession(
    private val client: CliHttpClient,
    backendUrl: String,
    initialToken: String,
    private val nanoTime: () -> Long = System::nanoTime,
    private val refreshToken: () -> String
) : AutoCloseable {
    private val endpoint = "${backendUrl.trimEnd('/')}/api/crowdstrike/servers/import/runs"
    private val token = AtomicReference(initialToken)
    private val failure = AtomicReference<Throwable?>(null)
    private var refreshedAt = nanoTime()
    private val log = LoggerFactory.getLogger(CrowdStrikeImportSession::class.java)
    private val runId = startRun()
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "crowdstrike-import-heartbeat").apply { isDaemon = true }
    }
    private var successful = false
    private val finalization = AtomicReference<FutureTask<Boolean>?>(null)
    private val shutdownHook = Thread({ finishOnShutdown() }, "crowdstrike-import-shutdown")

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        executor.scheduleAtFixedRate({
            try {
                heartbeat()
            } catch (e: Exception) {
                failure.compareAndSet(null, e)
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    private fun startRun(): String {
        val (status, body) = client.postMapWithStatus(endpoint, emptyMap<String, String>(), token.get())
        if (status == 409) {
            val expiresAt = (body?.get("expiresAt") as? String)?.let {
                runCatching { java.time.Instant.parse(it) }.getOrNull()
            }
            error("Another CrowdStrike import is active" +
                (expiresAt?.let { " (lease expires at $it)" } ?: "") +
                ". Wait for it to finish or for its lease to expire before retrying.")
        }
        check(status == 200) { "Could not start CrowdStrike import run (HTTP $status)" }
        return body?.get("runId")?.toString() ?: error("Could not start CrowdStrike import run: missing run ID")
    }

    internal fun heartbeat() {
        // Renew through the normal login path so long imports retain backend access.
        if (nanoTime() - refreshedAt >= TimeUnit.MINUTES.toNanos(20)) {
            token.set(refreshToken())
            refreshedAt = nanoTime()
        }
        check(client.postMap("$endpoint/$runId/heartbeat", emptyMap<String, String>(), token.get()) != null) {
            "CrowdStrike import heartbeat failed"
        }
    }

    fun authToken(): String {
        failure.get()?.let { throw IllegalStateException("CrowdStrike import lease lost", it) }
        return token.get()
    }

    fun complete(successful: Boolean) {
        authToken()
        this.successful = successful
    }

    override fun close() {
        try {
            finishOnce(successful && failure.get() == null)
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (e: IllegalStateException) {
                log.debug("Import shutdown cleanup already running: runId={}", runId)
            }
        }
    }

    internal fun finishOnShutdown() {
        try {
            finishOnce(false)
        } catch (e: Exception) {
            log.warn("Import shutdown cleanup failed: runId={} outcome=lease-expiry-fallback", runId)
        }
    }

    private fun finishOnce(successful: Boolean) {
        executor.shutdownNow()
        val task = FutureTask {
            client.postMap("$endpoint/$runId/finish?successful=$successful",
                emptyMap<String, String>(), token.get()) != null
        }
        if (finalization.compareAndSet(null, task)) {
            Thread(task, "crowdstrike-import-finish").apply { isDaemon = true }.start()
        }
        // A stalled HTTP request must not prevent Ctrl+C from terminating the CLI.
        try {
            check(requireNotNull(finalization.get()).get(5, TimeUnit.SECONDS)) {
                "Could not finalize CrowdStrike import run; its lease will expire"
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            requireNotNull(finalization.get()).cancel(true)
            throw IllegalStateException("Import cleanup timed out; its lease will expire", e)
        }
    }
}
