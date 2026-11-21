package com.secman.crowdstrike

import java.time.LocalDateTime

/** CrowdStrike's remediation clock starts at the first detection on this host. */
object SlaAnchor {
    fun isOverdue(detectedAt: LocalDateTime?, thresholdDays: Int, now: LocalDateTime): Boolean =
        detectedAt?.isBefore(now.minusDays(thresholdDays.toLong())) == true
}
