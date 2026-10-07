package com.secman.cli.service

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    private val runId = client.postMap(endpoint, emptyMap<String, String>(), token.get())
        ?.get("runId")?.toString() ?: error("Could not start CrowdStrike import run")
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "crowdstrike-import-heartbeat").apply { isDaemon = true }
    }
    private var successful = false

    init {
        executor.scheduleAtFixedRate({
            try {
                heartbeat()
            } catch (e: Exception) {
                failure.compareAndSet(null, e)
            }
        }, 30, 30, TimeUnit.SECONDS)
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
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        check(client.postMap("$endpoint/$runId/finish?successful=${successful && failure.get() == null}",
            emptyMap<String, String>(), token.get()) != null) { "Could not finalize CrowdStrike import run" }
    }
}
