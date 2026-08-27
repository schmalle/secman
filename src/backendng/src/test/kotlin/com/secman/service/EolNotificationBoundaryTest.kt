package com.secman.service

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The address [EolNotificationService] resolves becomes an SMTP recipient, part
 * of a stored notification result and a log line. That is three injection sinks
 * from one value, so the boundary rejects rather than repairs — an address is
 * either valid as-is or it is dropped (CLAUDE.md §A03, §A07, §A09).
 *
 * This mirrors the tightening already applied to the AWS account risk-assessment
 * owner address, where `[^@]+@[^@]+\.[^@]+` was found to accept CR/LF and commas.
 *
 * ID prefix: ENB-*
 */
class EolNotificationBoundaryTest {

    private val service = EolNotificationService(
        eolFindingRepository = mockk(relaxed = true),
        awsAccountRecipientResolver = mockk(relaxed = true),
        userRepository = mockk(relaxed = true),
        emailService = mockk(relaxed = true),
        eolFindingTableRenderer = EolFindingTableRenderer()
    )

    @Test
    @DisplayName("ENB-001: accepts ordinary addresses and normalizes case")
    fun acceptsValidAddresses() {
        assertThat(service.normalizeEmail("Owner@Example.com")).isEqualTo("owner@example.com")
        assertThat(service.normalizeEmail("  first.last+tag@sub.example.co.uk  "))
            .isEqualTo("first.last+tag@sub.example.co.uk")
    }

    @Test
    @DisplayName("ENB-002: rejects header-injection characters instead of stripping them")
    fun rejectsHeaderInjection() {
        // Stripping would turn an attack into a *delivered* mail to a mangled
        // address; dropping the recipient is the only safe outcome.
        listOf(
            "owner@example.com\r\nBcc: attacker@evil.example",
            "owner@example.com\nSubject: spoofed",
            "owner@example.com,attacker@evil.example",
            "owner@example.com;attacker@evil.example",
            "<owner@example.com>",
            "Owner Name <owner@example.com>"
        ).forEach { candidate ->
            assertThat(service.normalizeEmail(candidate))
                .describedAs("candidate %s", candidate.replace("\r", "\\r").replace("\n", "\\n"))
                .isNull()
        }
    }

    @Test
    @DisplayName("ENB-003: rejects malformed, empty and over-long addresses")
    fun rejectsMalformed() {
        listOf(
            null,
            "",
            "   ",
            "not-an-email",
            "@example.com",
            "owner@",
            "owner@example",          // no TLD
            "owner@@example.com",
            "a".repeat(250) + "@example.com"
        ).forEach { candidate ->
            assertThat(service.normalizeEmail(candidate))
                .describedAs("candidate %s", candidate ?: "null")
                .isNull()
        }
    }
}
