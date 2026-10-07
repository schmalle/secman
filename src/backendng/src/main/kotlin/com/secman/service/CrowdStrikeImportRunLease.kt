package com.secman.service

import jakarta.inject.Singleton
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Bounds a CLI run's lifetime without interpreting pauses in Falcon requests as completion. */
@Singleton
class CrowdStrikeImportRunLease {
    internal var clock: Clock = Clock.systemUTC()
    private data class Run(val id: String, val owner: String, var expiresAt: Instant)
    private var run: Run? = null

    @Synchronized
    fun start(owner: String): String {
        check(active() == null) { "An import run is already active" }
        val id = UUID.randomUUID().toString()
        run = Run(id, owner, clock.instant().plusSeconds(600))
        return id
    }

    @Synchronized
    fun heartbeat(id: String, owner: String) {
        requireOwner(id, owner)
        run!!.expiresAt = clock.instant().plusSeconds(600)
    }

    @Synchronized
    fun requireOwner(id: String, owner: String) {
        val current = active()
        check(current != null && current.id == id && current.owner == owner) { "Import run is unavailable" }
    }

    @Synchronized
    fun finish(id: String, owner: String) {
        requireOwner(id, owner)
        run = null
    }

    @Synchronized
    fun activeRunId(): String? = active()?.id

    private fun active(): Run? {
        if (run?.expiresAt?.isAfter(clock.instant()) == false) run = null
        return run
    }
}
