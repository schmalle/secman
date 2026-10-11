package com.secman.service

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Notification eligibility uses actual Falcon contact, never SecMan import time. */
internal data class NotificationAssetSeenWindow(val startUtc: LocalDateTime, val endUtc: LocalDateTime) {
    fun includes(seenAt: LocalDateTime?): Boolean =
        seenAt != null && !seenAt.isBefore(startUtc) && !seenAt.isAfter(endUtc)

    companion object {
        fun current(clock: Clock = Clock.systemUTC()): NotificationAssetSeenWindow {
            val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            return NotificationAssetSeenWindow(now.minusHours(72), now)
        }
    }
}
