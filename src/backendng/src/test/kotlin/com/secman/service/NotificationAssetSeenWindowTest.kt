package com.secman.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class NotificationAssetSeenWindowTest {
    @Test
    fun `window is exactly 24 hours in UTC across daylight saving changes`() {
        val clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneId.of("Europe/Berlin"))
        val window = NotificationAssetSeenWindow.current(clock)

        assertThat(window.startUtc.toString()).isEqualTo("2026-10-24T12:00")
        assertThat(window.endUtc.toString()).isEqualTo("2026-10-25T12:00")
        assertThat(window.includes(window.startUtc)).isTrue()
        assertThat(window.includes(window.endUtc)).isTrue()
        assertThat(window.includes(window.startUtc.minusSeconds(1))).isFalse()
        assertThat(window.includes(window.endUtc.plusSeconds(1))).isFalse()
        assertThat(window.includes(null)).isFalse()
    }
}
