package com.secman.service

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Berlin calendar boundaries expressed in the UTC timestamps stored for Falcon contact. */
internal data class FalconSeenTodayWindow(val startUtc: LocalDateTime, val endUtc: LocalDateTime) {
    companion object {
        fun today(clock: Clock = Clock.systemUTC()): FalconSeenTodayWindow {
            val zone = ZoneId.of("Europe/Berlin")
            val date = LocalDate.now(clock.withZone(zone))
            return FalconSeenTodayWindow(
                LocalDateTime.ofInstant(date.atStartOfDay(zone).toInstant(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(date.plusDays(1).atStartOfDay(zone).toInstant(), ZoneOffset.UTC)
            )
        }
    }
}
