package com.secman.service

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Match MariaDB's DATETIME(6) representation when storing or comparing Falcon first_seen. */
internal fun Instant.toFalconEnrollmentTime(): LocalDateTime =
    LocalDateTime.ofInstant(truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC)
