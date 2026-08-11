package com.secman.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The one boundary check for anything that becomes an SMTP recipient.
 *
 * This is a security control, not input hygiene: the pattern lived in two copies before and
 * both had to be kept in step by hand. The cases below pin *why* each character class is
 * excluded, so a future "let's be more permissive" edit has to argue with a named attack.
 */
class EmailAddressValidatorTest {

    @Test
    fun `ordinary addresses are accepted`() {
        for (address in listOf(
            "alice@corp.com",
            "alice.bob@corp.co.uk",
            "alice+aws@corp.com",
            "ALICE@CORP.COM",
            "a@b.co",
            "first_last@sub.domain.example"
        )) {
            assertThat(EmailAddressValidator.isValidRecipient(address))
                .describedAs(address)
                .isTrue()
        }
    }

    @Test
    fun `surrounding whitespace is trimmed, not rejected`() {
        assertThat(EmailAddressValidator.isValidRecipient("  alice@corp.com  ")).isTrue()
    }

    @Test
    fun `a comma is rejected because InternetAddress parse would split it into two recipients`() {
        assertThat(EmailAddressValidator.isValidRecipient("alice@corp.com,evil@bad.com")).isFalse()
        assertThat(EmailAddressValidator.isValidRecipient("alice@corp.com;evil@bad.com")).isFalse()
    }

    @Test
    fun `CR and LF are rejected because they would reach a mail header`() {
        assertThat(EmailAddressValidator.isValidRecipient("alice@corp.com\nBcc: evil@bad.com")).isFalse()
        assertThat(EmailAddressValidator.isValidRecipient("alice@corp.com\r\nBcc: evil@bad.com")).isFalse()
        assertThat(EmailAddressValidator.isValidRecipient("alice @corp.com")).isFalse()
    }

    @Test
    fun `address-group and quoting syntax is rejected`() {
        for (address in listOf(
            "Alice <alice@corp.com>",
            "group:alice@corp.com",
            "\"alice\"@corp.com",
            "alice\\@corp.com@evil.com"
        )) {
            assertThat(EmailAddressValidator.isValidRecipient(address))
                .describedAs(address)
                .isFalse()
        }
    }

    @Test
    fun `structurally incomplete addresses are rejected`() {
        for (address in listOf("", "   ", "alice", "alice@", "@corp.com", "alice@corp", "alice@@corp.com")) {
            assertThat(EmailAddressValidator.isValidRecipient(address))
                .describedAs("'$address'")
                .isFalse()
        }
        assertThat(EmailAddressValidator.isValidRecipient(null)).isFalse()
    }

    @Test
    fun `an over-long address is rejected rather than truncated`() {
        val long = "a".repeat(EmailAddressValidator.MAX_LENGTH) + "@corp.com"
        assertThat(EmailAddressValidator.isValidRecipient(long)).isFalse()
        // Truncating would silently deliver to a different address than the one supplied.
        assertThat(long.length).isGreaterThan(EmailAddressValidator.MAX_LENGTH)
    }

    @Test
    fun `matchesPattern ignores the length cap so callers can report the two failures apart`() {
        val long = "a".repeat(EmailAddressValidator.MAX_LENGTH) + "@corp.com"
        assertThat(EmailAddressValidator.matchesPattern(long)).isTrue()
        assertThat(EmailAddressValidator.isValidRecipient(long)).isFalse()
        // The shape check still refuses everything the recipient check refuses on shape.
        assertThat(EmailAddressValidator.matchesPattern("alice@corp.com,evil@bad.com")).isFalse()
        assertThat(EmailAddressValidator.matchesPattern(null)).isFalse()
    }

    @Test
    fun `sanitizeForEcho strips line breaks so a rejected value cannot forge a log line`() {
        assertThat(EmailAddressValidator.sanitizeForEcho("alice@corp.com\nERROR forged")).doesNotContain("\n")
        assertThat(EmailAddressValidator.sanitizeForEcho("a\r\nb")).isEqualTo("a  b".trim())
        assertThat(EmailAddressValidator.sanitizeForEcho(null)).isEmpty()
        assertThat(EmailAddressValidator.sanitizeForEcho("")).isEmpty()
    }

    @Test
    fun `sanitizeForEcho caps the length so a hostile value cannot flood a log`() {
        val flood = "x".repeat(5000)
        assertThat(EmailAddressValidator.sanitizeForEcho(flood).length)
            .isEqualTo(EmailAddressValidator.MAX_LENGTH)
        assertThat(EmailAddressValidator.sanitizeForEcho(flood, maxLength = 10).length).isEqualTo(10)
    }
}
