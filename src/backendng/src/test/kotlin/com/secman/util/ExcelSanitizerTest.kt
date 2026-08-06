package com.secman.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * ExcelSanitizer is the only defence between user-controlled strings and the
 * formula engine of whatever spreadsheet application opens an exported file, and
 * it had no tests. Every export path (assets, vulnerabilities, exception requests,
 * application register) runs through it, so a regression here is a stored-payload
 * command-execution bug in a file the recipient opens by hand — not something any
 * E2E gate would notice, because the exported file is never opened by the runner.
 *
 * ID prefix: ES-*
 */
class ExcelSanitizerTest {

    @Test
    @DisplayName("ES-001: neutralizes every documented formula prefix")
    fun neutralizesFormulaPrefixes() {
        listOf("=", "+", "-", "@").forEach { prefix ->
            assertThat(ExcelSanitizer.sanitize("${prefix}cmd|'/c calc'!A1"))
                .describedAs("prefix %s", prefix)
                .isEqualTo("'${prefix}cmd|'/c calc'!A1")
        }
    }

    @Test
    @DisplayName("ES-002: a payload hidden behind leading whitespace is still neutralized")
    fun neutralizesAfterLeadingWhitespace() {
        // The check runs on the trimmed value on purpose: Excel ignores the leading
        // spaces, so testing the raw first character would let "  =HYPERLINK(...)"
        // through as a live formula.
        assertThat(ExcelSanitizer.sanitize("   =HYPERLINK(\"http://evil.example\",\"Click\")"))
            .isEqualTo("'=HYPERLINK(\"http://evil.example\",\"Click\")")
    }

    @Test
    @DisplayName("ES-003: leading control whitespace is stripped rather than escaped")
    fun stripsLeadingControlWhitespace() {
        // FORMULA_PREFIXES lists '\t' and '\r', but trim() removes them before the
        // prefix check ever runs, so those two entries can never fire. That is safe
        // rather than a hole — the tab is gone, so no parser can strip it and expose
        // a formula underneath — but it is the kind of dead branch someone later
        // "fixes" by dropping the trim(). This pins the resulting behaviour so that
        // change fails loudly.
        listOf("\t", "\r", "\n").forEach { control ->
            assertThat(ExcelSanitizer.sanitize("${control}plain text"))
                .describedAs("control %s before safe text", control.encodeEscapes())
                .isEqualTo("plain text")
            assertThat(ExcelSanitizer.sanitize("$control=cmd"))
                .describedAs("control %s before a formula", control.encodeEscapes())
                .isEqualTo("'=cmd")
        }
    }

    @Test
    @DisplayName("ES-004: ordinary values pass through trimmed and unescaped")
    fun leavesSafeValuesAlone() {
        assertThat(ExcelSanitizer.sanitize("CVE-2024-1234")).isEqualTo("CVE-2024-1234")
        assertThat(ExcelSanitizer.sanitize("  web-01  ")).isEqualTo("web-01")
        assertThat(ExcelSanitizer.sanitize("Apache HTTP Server 2.4.41")).isEqualTo("Apache HTTP Server 2.4.41")
    }

    @Test
    @DisplayName("ES-005: a formula character anywhere but the front is not escaped")
    fun onlyEscapesLeadingPosition() {
        // Escaping mid-string values would corrupt legitimate data: CVSS vectors,
        // version ranges and email addresses all contain these characters.
        assertThat(ExcelSanitizer.sanitize("CVSS:3.1/AV:N/AC:L")).isEqualTo("CVSS:3.1/AV:N/AC:L")
        assertThat(ExcelSanitizer.sanitize("owner@example.com")).isEqualTo("owner@example.com")
        assertThat(ExcelSanitizer.sanitize("1.2.3-4")).isEqualTo("1.2.3-4")
    }

    @Test
    @DisplayName("ES-006: null and blank collapse to an empty cell, never to \"null\"")
    fun handlesNullAndBlank() {
        assertThat(ExcelSanitizer.sanitize(null)).isEmpty()
        assertThat(ExcelSanitizer.sanitize("")).isEmpty()
        assertThat(ExcelSanitizer.sanitize("   ")).isEmpty()
    }

    @Test
    @DisplayName("ES-007: sanitizing twice does not stack quotes")
    fun secondPassIsStable() {
        // Export paths compose (a sanitized value handed to another writer), and a
        // second apostrophe would become visible text in the opened sheet.
        val once = ExcelSanitizer.sanitize("=1+1")
        assertThat(once).isEqualTo("'=1+1")
        assertThat(ExcelSanitizer.sanitize(once)).isEqualTo(once)
    }

    /** Renders control characters readably in assertion failure messages. */
    private fun String.encodeEscapes(): String =
        replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
}
