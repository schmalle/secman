package com.secman.exception

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A `@Size` violation on a collection carries the whole collection as its invalid value, so
 * logging it verbatim writes the entire rejected request body to the backend log. Six
 * oversized CrowdStrike hosts did exactly that on 2026-08-25: 27MB of log from six
 * violations. These tests pin the bound.
 */
@DisplayName("ValidationExceptionHandler invalid-value rendering")
class ValidationExceptionHandlerTest {

    @Test
    @DisplayName("a rejected collection renders as its size, never its elements")
    fun collectionRendersAsSizeOnly() {
        val rejected = (1..66_859).map { "vulnerability-row-$it" }

        val rendered = ValidationExceptionHandler.renderInvalidValue(rejected)

        assertThat(rendered).isEqualTo("<collection of 66859 element(s)>")
        assertThat(rendered).doesNotContain("vulnerability-row-")
    }

    @Test
    @DisplayName("a long string is truncated and reports its true length")
    fun longStringIsTruncated() {
        val rendered = ValidationExceptionHandler.renderInvalidValue("x".repeat(5_000))

        assertThat(rendered).hasSizeLessThan(200)
        assertThat(rendered).endsWith("... (5000 chars)")
    }

    @Test
    @DisplayName("a long non-string value is truncated too")
    fun longNonStringIsTruncated() {
        // Rendering must not depend on the value happening to be a String.
        val rendered = ValidationExceptionHandler.renderInvalidValue(java.math.BigInteger.TEN.pow(500))

        assertThat(rendered).hasSizeLessThan(200)
        assertThat(rendered).contains("chars)")
    }

    @Test
    @DisplayName("short and blank values keep their existing rendering")
    fun shortValuesAreUnchanged() {
        assertThat(ValidationExceptionHandler.renderInvalidValue(null)).isEqualTo("null")
        assertThat(ValidationExceptionHandler.renderInvalidValue("")).isEqualTo("\"\" (blank string)")
        assertThat(ValidationExceptionHandler.renderInvalidValue(42)).isEqualTo("42")
        assertThat(ValidationExceptionHandler.renderInvalidValue("openssl")).isEqualTo("openssl")
    }
}
