package com.secman.service

import jakarta.inject.Singleton
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Singleton
class ActiveUserTracker(
    private val clock: Clock = Clock.systemUTC(),
    val activeWindow: Duration = Duration.ofMinutes(15)
) {
    private val lastActivityByUsername = ConcurrentHashMap<String, Instant>()

    fun markActive(username: String) {
        if (username.isBlank()) return
        lastActivityByUsername[username] = Instant.now(clock)
    }

    fun remove(username: String) {
        lastActivityByUsername.remove(username)
    }

    fun countActiveUsers(): Int {
        pruneExpired()
        return lastActivityByUsername.size
    }

    private fun pruneExpired() {
        val cutoff = Instant.now(clock).minus(activeWindow)
        lastActivityByUsername.entries.removeIf { (_, lastActivity) ->
            lastActivity.isBefore(cutoff) || lastActivity == cutoff
        }
    }
}
