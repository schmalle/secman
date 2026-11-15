package com.secman.crowdstrike

import java.time.LocalDateTime

/**
 * When a finding's remediation clock starts: the later of "first detected on this host"
 * and "a fix became available". A finding is overdue once `anchor + thresholdDays` has
 * passed, so it can be overdue neither before we saw it nor before it could be fixed.
 *
 * Example: CVE fix published on day x, CrowdStrike first detects it on the host on day
 * z = x + y. The anchor is z and the finding becomes overdue on z + 30. If the host was
 * detected before any fix existed (z < x), the anchor is x.
 *
 * This is the only Kotlin definition. The backend stores the same rule as the generated
 * column `vulnerability.sla_anchor_at` (`Vulnerability.SLA_ANCHOR_SQL`, V299), and the
 * two must stay identical. See docs/CROWDSTRIKE_IMPORT.md §SLA anchor.
 */
object SlaAnchor {

    fun of(detectedAt: LocalDateTime, patchPublicationDate: LocalDateTime?): LocalDateTime =
        if (patchPublicationDate != null && patchPublicationDate.isAfter(detectedAt)) patchPublicationDate
        else detectedAt

    /**
     * Same strict comparison as the backend filter `sla_anchor_at < now - thresholdDays`.
     * An unknown detection date is not overdue here; the persisted import decides how to
     * date such a row, and this is only used for pre-import summaries.
     */
    fun isOverdue(
        detectedAt: LocalDateTime?,
        patchPublicationDate: LocalDateTime?,
        thresholdDays: Int,
        now: LocalDateTime
    ): Boolean {
        if (detectedAt == null) return false
        return of(detectedAt, patchPublicationDate).isBefore(now.minusDays(thresholdDays.toLong()))
    }
}
