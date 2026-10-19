package com.secman.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class FalconSeenTodayWindowTest {
    @Test
    fun `Berlin day starts on the previous UTC date`() {
        val window = FalconSeenTodayWindow.today(Clock.fixed(Instant.parse("2026-10-08T22:30:00Z"), ZoneOffset.UTC))
        assertThat(window.startUtc).isEqualTo(LocalDateTime.parse("2026-10-08T22:00:00"))
        assertThat(window.endUtc).isEqualTo(LocalDateTime.parse("2026-10-09T22:00:00"))
    }

    @Test
    fun `daylight saving days have calendar boundaries rather than a fixed 24 hours`() {
        for ((instant, start, end) in listOf(
            Triple("2026-03-29T12:00:00Z", "2026-03-28T23:00:00", "2026-03-29T22:00:00"),
            Triple("2026-10-25T12:00:00Z", "2026-10-24T22:00:00", "2026-10-25T23:00:00")
        )) {
            val window = FalconSeenTodayWindow.today(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))
            assertThat(window.startUtc).isEqualTo(LocalDateTime.parse(start))
            assertThat(window.endUtc).isEqualTo(LocalDateTime.parse(end))
        }
    }
}
