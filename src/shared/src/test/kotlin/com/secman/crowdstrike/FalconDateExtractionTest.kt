package com.secman.crowdstrike

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Falcon hands us three dates that mean three different things, and the extractor
 * used to blur two of them together:
 *
 *  - `created_timestamp` — when Falcon created *this finding on this host*
 *  - `cve.published_date` — when the CVE was disclosed
 *  - `remediation.vendor_release_date` — when a fix became available
 *
 * `extractPatchPublicationDate` asked `cve.published_date` FIRST, so the column
 * named `patch_publication_date` — and used as the SLA anchor when
 * `secman.vulnerability.use-patch-publication-date` is on — almost always held the
 * CVE disclosure date. "How long since a fix existed?" was silently answered with
 * "how long since the CVE was published".
 *
 * These tests pin the corrected preference order **and** the deliberate CVE
 * fallback. The fallback matters as much as the reordering:
 * `require-patch-publication-date` filters imports on this field being non-null, so
 * narrowing it to true patch dates only would start silently dropping rows.
 *
 * ID prefix: FDE-*
 */
class FalconDateExtractionTest {

    private fun patchDate(vuln: Map<String, Any?>) = FalconTimestamps.patchPublicationDate(vuln)

    private fun cveDate(vuln: Map<String, Any?>) = FalconTimestamps.cvePublishedDate(vuln)

    @Test
    @DisplayName("FDE-001: a real vendor release date wins over the CVE publication date")
    fun vendorReleaseDateWins() {
        val vuln = mapOf(
            "cve" to mapOf("published_date" to "2023-01-15T00:00:00Z"),
            "remediation" to mapOf("vendor_release_date" to "2026-03-20T00:00:00Z")
        )

        assertThat(patchDate(vuln)?.toLocalDate().toString()).isEqualTo("2026-03-20")
        assertThat(cveDate(vuln)?.toLocalDate().toString()).isEqualTo("2023-01-15")
    }

    @Test
    @DisplayName("FDE-002: remediation.published_date is preferred over the CVE date too")
    fun remediationPublishedDateWins() {
        val vuln = mapOf(
            "cve" to mapOf("published_date" to "2023-01-15T00:00:00Z"),
            "remediation" to mapOf("published_date" to "2026-02-02T00:00:00Z")
        )

        assertThat(patchDate(vuln)?.toLocalDate().toString()).isEqualTo("2026-02-02")
    }

    /**
     * The load-bearing case. Dropping this fallback would make the field null for
     * most rows, and `require-patch-publication-date` would then filter them out of
     * the import entirely — data loss disguised as a naming fix.
     */
    @Test
    @DisplayName("FDE-003: with no remediation date at all, the CVE date is still used")
    fun cveDateRemainsTheLastResort() {
        val vuln = mapOf("cve" to mapOf("published_date" to "2023-01-15T00:00:00Z"))

        assertThat(patchDate(vuln)).isNotNull()
        assertThat(patchDate(vuln)?.toLocalDate().toString()).isEqualTo("2023-01-15")
    }

    @Test
    @DisplayName("FDE-004: the CVE date is read only from the cve object, never from remediation")
    fun cveDateDoesNotBorrowFromRemediation() {
        val vuln = mapOf("remediation" to mapOf("vendor_release_date" to "2026-03-20T00:00:00Z"))

        assertThat(cveDate(vuln)).isNull()
        assertThat(patchDate(vuln)).isNotNull()
    }

    @Test
    @DisplayName("FDE-005: absent dates stay null rather than defaulting to now")
    fun absentStaysNull() {
        assertThat(patchDate(emptyMap())).isNull()
        assertThat(cveDate(emptyMap())).isNull()
        assertThat(patchDate(mapOf("cve" to mapOf("published_date" to "")))).isNull()
        assertThat(cveDate(mapOf("cve" to mapOf("published_date" to "not-a-date")))).isNull()
    }

    @Test
    @DisplayName("FDE-006: a date-only CVE value parses as midnight")
    fun dateOnlyValueParses() {
        val vuln = mapOf("cve" to mapOf("published_date" to "2023-01-15"))

        assertThat(cveDate(vuln)).isEqualTo(LocalDateTime.of(2023, 1, 15, 0, 0, 0))
    }
}
