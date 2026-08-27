package com.secman.service

import com.secman.crowdstrike.FalconTimestamps
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Falcon emits `created_timestamp` as a UTC instant (`"2026-01-10T22:18:26Z"`).
 *
 * The ad-hoc lookup path used to parse it by *stripping* the `Z` and handing the
 * remainder to `LocalDateTime.parse`, which reinterprets a UTC instant as
 * system-local time rather than converting it. On any host offset from UTC that
 * shifts the value — and because "days open" is a whole-day
 * `ChronoUnit.DAYS.between`, a shift across midnight changes the reported age by a
 * day. Four separate copies of this parsing existed; the persisted path's was
 * correct, the lookup path's was not.
 *
 * These pin the contract of the single [FalconTimestamps] they now all share, so
 * the copies cannot reappear and drift. The assertions are about an *instant*,
 * never about a formatted string.
 *
 * ID prefix: CTS-*
 */
class CrowdStrikeTimestampParsingTest {

    private fun expectedFor(iso: String): LocalDateTime =
        LocalDateTime.ofInstant(Instant.parse(iso), ZoneId.systemDefault())

    @Test
    @DisplayName("CTS-001: a UTC instant is converted to local time, not silently relabelled")
    fun utcInstantIsConvertedNotRelabelled() {
        val raw = "2026-01-10T22:18:26Z"

        assertThat(FalconTimestamps.parse(raw)).isEqualTo(expectedFor(raw))
    }

    @Test
    @DisplayName("CTS-002: fractional seconds do not defeat the offset handling")
    fun fractionalSecondsStillConvert() {
        val raw = "2026-01-10T22:18:26.123456Z"

        assertThat(FalconTimestamps.parse(raw)).isEqualTo(expectedFor(raw))
    }

    @Test
    @DisplayName("CTS-003: an explicit non-UTC offset is honoured")
    fun explicitOffsetIsHonoured() {
        val raw = "2026-01-10T22:18:26+05:30"

        assertThat(FalconTimestamps.parse(raw)).isEqualTo(expectedFor(raw))
    }

    @Test
    @DisplayName("CTS-004: epoch seconds and milliseconds are disambiguated by magnitude")
    fun epochFormsAreSupported() {
        val instant = Instant.parse("2026-01-10T22:18:26Z")
        val expected = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())

        assertThat(FalconTimestamps.parse(instant.epochSecond)).isEqualTo(expected)
        assertThat(FalconTimestamps.parse(instant.toEpochMilli())).isEqualTo(expected)
        // Same values arriving as strings, which the Falcon JSON sometimes does.
        assertThat(FalconTimestamps.parse(instant.epochSecond.toString())).isEqualTo(expected)
        assertThat(FalconTimestamps.parse(instant.toEpochMilli().toString())).isEqualTo(expected)
    }

    @Test
    @DisplayName("CTS-005: an offset-free local timestamp is taken at face value")
    fun offsetFreeValueIsTakenLiterally() {
        assertThat(FalconTimestamps.parse("2026-01-10T22:18:26"))
            .isEqualTo(LocalDateTime.of(2026, 1, 10, 22, 18, 26))
        assertThat(FalconTimestamps.parse("2026-01-10 22:18:26"))
            .isEqualTo(LocalDateTime.of(2026, 1, 10, 22, 18, 26))
    }

    @Test
    @DisplayName("CTS-006: a date-only value is midnight, not a parse failure")
    fun dateOnlyIsMidnight() {
        assertThat(FalconTimestamps.parse("2026-01-10"))
            .isEqualTo(LocalDateTime.of(2026, 1, 10, 0, 0, 0))
    }

    /**
     * The single most important case. Returning "now" for an unreadable date makes
     * the finding look zero days old, which keeps it out of every overdue
     * calculation — a silent security miss, not a formatting nicety.
     */
    @Test
    @DisplayName("CTS-007: absent or unreadable stays unknown — never 'now'")
    fun unknownStaysUnknown() {
        assertThat(FalconTimestamps.parse(null)).isNull()
        assertThat(FalconTimestamps.parse("")).isNull()
        assertThat(FalconTimestamps.parse("   ")).isNull()
        assertThat(FalconTimestamps.parse("not-a-date")).isNull()
        assertThat(FalconTimestamps.parse("2026-13-45T99:99:99Z")).isNull()
    }

    @Test
    @DisplayName("CTS-008: values echoed into logs are CR/LF-stripped and capped")
    fun logSanitizationStripsNewlines() {
        val forged = "2026-01-10\r\nWARN  Fabricated log line"

        val sanitized = FalconTimestamps.sanitizeForLog(forged)

        assertThat(sanitized).doesNotContain("\r").doesNotContain("\n")
        assertThat(sanitized).hasSizeLessThanOrEqualTo(120)
        assertThat(FalconTimestamps.sanitizeForLog(null)).isEqualTo("null")
    }

    /**
     * The invariant this whole file exists for, asserted on the *type* rather than on
     * a value: `CrowdStrikeVulnerabilityDto.detectedAt` must stay nullable.
     *
     * An earlier revision returned null correctly from the parser and then had every
     * caller coerce it straight back to `LocalDateTime.now()`, because the DTO field
     * was `@NotNull`. That moved the defect rather than fixing it, and left a KDoc
     * asserting a rule the code did not keep. If someone makes this field non-null
     * again, the coercion necessarily comes back — so guard the type, not a sample.
     */
    @Test
    @DisplayName("CTS-009: the DTO keeps detectedAt nullable so unknown cannot be coerced to now")
    fun dtoKeepsDetectedAtNullable() {
        val field = com.secman.dto.CrowdStrikeVulnerabilityDto::class.members
            .single { it.name == "detectedAt" }

        assertThat(field.returnType.isMarkedNullable)
            .describedAs("CrowdStrikeVulnerabilityDto.detectedAt must stay nullable")
            .isTrue()
    }
}
