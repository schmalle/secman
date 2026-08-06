package com.secman.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@DisplayName("ActiveUserTracker Tests")
class ActiveUserTrackerTest {

    @Test
    fun `counts distinct users active within the window`() {
        val clock = MutableClock(Instant.parse("2026-05-21T10:00:00Z"))
        val tracker = ActiveUserTracker(clock = clock, activeWindow = Duration.ofMinutes(15))

        tracker.markActive("alice")
        tracker.markActive("alice")
        tracker.markActive("bob")

        assertThat(tracker.countActiveUsers()).isEqualTo(2)
    }

    @Test
    fun `excludes users whose last activity is older than the window`() {
        val clock = MutableClock(Instant.parse("2026-05-21T10:00:00Z"))
        val tracker = ActiveUserTracker(clock = clock, activeWindow = Duration.ofMinutes(15))

        tracker.markActive("alice")
        clock.now = Instant.parse("2026-05-21T10:14:59Z")
        assertThat(tracker.countActiveUsers()).isEqualTo(1)

        clock.now = Instant.parse("2026-05-21T10:15:01Z")
        assertThat(tracker.countActiveUsers()).isEqualTo(0)
    }

    @Test
    fun `removes user on logout`() {
        val clock = MutableClock(Instant.parse("2026-05-21T10:00:00Z"))
        val tracker = ActiveUserTracker(clock = clock, activeWindow = Duration.ofMinutes(15))

        tracker.markActive("alice")
        tracker.markActive("bob")

        tracker.remove("alice")

        assertThat(tracker.countActiveUsers()).isEqualTo(1)
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
