package com.secman.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.api.Test

/** Pins the strict identifiers permitted in ordinary inventory responses. */
class CloudIdentifierDisplayTest {
    @ParameterizedTest
    @NullAndEmptySource
    @CsvSource("12345678901", "1234567890123", "84db0360-b850-41f3-891f-6e7b2e327303", "'  '")
    fun `invalid account IDs are hidden`(value: String?) {
        assertThat(CloudIdentifierDisplay.accountId(value)).isNull()
    }

    @Test
    fun `valid account IDs are trimmed`() {
        assertThat(CloudIdentifierDisplay.accountId(" 123456789012 ")).isEqualTo("123456789012")
    }

    @ParameterizedTest
    @NullAndEmptySource
    @CsvSource("i-xyz", "3f41-43bc-9900-ebe7c25f4140", "i-123456789", "i-1234567890123456g")
    fun `invalid instance IDs are hidden`(value: String?) {
        assertThat(CloudIdentifierDisplay.instanceId(value)).isNull()
    }

    @ParameterizedTest
    @CsvSource("I-1234ABCD,i-1234abcd", "i-0123456789ABCDEF0,i-0123456789abcdef0")
    fun `valid instance IDs are normalized`(value: String, expected: String) {
        assertThat(CloudIdentifierDisplay.instanceId(value)).isEqualTo(expected)
    }
}
