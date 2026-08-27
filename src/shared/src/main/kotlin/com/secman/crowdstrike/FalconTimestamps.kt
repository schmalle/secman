package com.secman.crowdstrike

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The single parser for every date CrowdStrike Falcon hands us.
 *
 * It exists because there were two of them. `CrowdStrikeApiClientImpl` (the
 * persisted import path) converted `"2026-01-10T22:18:26Z"` correctly via
 * `Instant.parse`, while `CrowdStrikeVulnerabilityService` (the ad-hoc lookup
 * behind the "Days Open" column) *stripped* the `Z` and handed the remainder to
 * `LocalDateTime.parse` — which relabels a UTC instant as system-local time
 * instead of converting it. On a host offset from UTC that shifts the value, and
 * because age is a whole-day `ChronoUnit.DAYS.between`, a shift across midnight
 * changed the reported age by a day. Two copies, one correct: exactly the drift
 * a shared helper prevents.
 *
 * Design notes:
 *  - **Absent or unparseable returns null, never "now".** A date we could not read
 *    must stay unknown. Defaulting to the current time reads as "detected today",
 *    i.e. zero days old, which quietly keeps the row out of every overdue
 *    calculation — a security miss dressed as a formatting fallback.
 *  - Values arrive from an external API and are echoed into logs, so anything
 *    logged here is CR/LF-stripped and length-capped (CLAUDE.md §A03).
 */
object FalconTimestamps {

    /**
     * Parse a Falcon timestamp in any shape the API emits.
     *
     * Accepted:
     *  - epoch seconds or milliseconds (any [Number])
     *  - an ISO-8601 instant with `Z` or an explicit offset — converted to local time
     *  - an offset-free ISO local date-time — taken at face value
     *  - `"yyyy-MM-dd HH:mm:ss"` (space separator) — taken at face value
     *  - `"yyyy-MM-dd"` — taken as midnight
     *
     * @return the parsed value, or null when absent or unrecognised.
     */
    fun parse(value: Any?): LocalDateTime? {
        if (value == null) return null

        if (value is Number) return fromEpoch(value.toLong())

        val text = value.toString().trim()
        if (text.isEmpty()) return null

        // A bare epoch may also arrive as a string.
        text.toLongOrNull()?.let { return fromEpoch(it) }

        return try {
            when {
                // Carries 'Z' or an offset: a real instant, so convert rather than relabel.
                OFFSET_SUFFIX.containsMatchIn(text) ->
                    LocalDateTime.ofInstant(OffsetDateTime.parse(text).toInstant(), ZoneId.systemDefault())
                text.contains("T") -> LocalDateTime.parse(text)
                text.contains(" ") -> LocalDateTime.parse(text.replace(" ", "T"))
                DATE_ONLY.matches(text) -> LocalDateTime.parse("${text}T00:00:00")
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Epoch seconds vs milliseconds, disambiguated by magnitude. The threshold is
     * 10^11: as seconds that is year 5138, and as milliseconds it is 1973 — so no
     * timestamp secman will ever see is ambiguous.
     */
    private fun fromEpoch(raw: Long): LocalDateTime? = try {
        val instant = if (raw > EPOCH_MILLIS_THRESHOLD) Instant.ofEpochMilli(raw) else Instant.ofEpochSecond(raw)
        LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
    } catch (_: Exception) {
        null
    }


    /**
     * Best available "a fix existed from" date for one Falcon vulnerability record.
     *
     * Genuine remediation dates first. The previous order asked `cve.published_date`
     * FIRST, so the field named "patch publication date" — used as the SLA anchor when
     * `secman.vulnerability.use-patch-publication-date` is on — almost always held the
     * CVE *disclosure* date, silently answering "how long since a fix existed?" with
     * "how long since the CVE was published".
     *
     * The CVE fields remain the LAST resort rather than being removed:
     * `require-patch-publication-date` filters imports on this being non-null, so
     * narrowing to true patch dates only would start dropping rows. Nothing that was
     * non-null before becomes null now — only the preference order changed.
     */
    fun patchPublicationDate(vuln: Map<*, *>): LocalDateTime? {
        val cve = vuln["cve"] as? Map<*, *>
        val remediation = vuln["remediation"] as? Map<*, *>
        return parse(
            remediation?.get("vendor_release_date")
                ?: remediation?.get("published_date")
                ?: vuln["patch_published_date"]
                ?: vuln["patch_publication_date"]
                ?: cve?.get("published_date")
                ?: cve?.get("published")
        )
    }

    /**
     * CVE disclosure date, read only from the `cve` object.
     *
     * Kept separate from [patchPublicationDate] on purpose: conflating the two is what
     * made a 2023 CVE indistinguishable from a 2025 one in the UI.
     */
    fun cvePublishedDate(vuln: Map<*, *>): LocalDateTime? {
        val cve = vuln["cve"] as? Map<*, *>
        return parse(cve?.get("published_date") ?: cve?.get("published"))
    }

    /** Strip CR/LF/TAB and cap length before an API value reaches a log line (§A03). */
    fun sanitizeForLog(value: Any?): String =
        value?.toString()?.replace(Regex("[\\r\\n\\t]"), "_")?.take(120) ?: "null"

    private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L
    private val OFFSET_SUFFIX = Regex("(Z|[+-]\\d{2}:?\\d{2})$")
    private val DATE_ONLY = Regex("\\d{4}-\\d{2}-\\d{2}")
}
