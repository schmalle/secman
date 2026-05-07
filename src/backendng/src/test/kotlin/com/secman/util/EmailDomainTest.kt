package com.secman.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmailDomainTest {

    @Test
    fun `extractDomain returns lowercase domain after the at sign`() {
        assertEquals("example.com", EmailDomain.extractDomain("alice@example.com"))
        assertEquals("example.com", EmailDomain.extractDomain("ALICE@Example.COM"))
        assertEquals("example.com", EmailDomain.extractDomain("  alice@example.com  "))
    }

    @Test
    fun `extractDomain returns null for malformed inputs`() {
        assertNull(EmailDomain.extractDomain(""))
        assertNull(EmailDomain.extractDomain("no-at-sign"))
        assertNull(EmailDomain.extractDomain("@no-local-part.com"))
        assertNull(EmailDomain.extractDomain("trailing-at@"))
        assertNull(EmailDomain.extractDomain("two@at@signs.com"))
        assertNull(EmailDomain.extractDomain(null))
    }

    @Test
    fun `sameDomain matches case-insensitively after at sign`() {
        assertTrue(EmailDomain.sameDomain("a@example.com", "B@EXAMPLE.COM"))
        assertTrue(EmailDomain.sameDomain("a@example.com", "b@example.com"))
    }

    @Test
    fun `sameDomain rejects subdomains as different`() {
        assertFalse(EmailDomain.sameDomain("a@example.com", "b@eu.example.com"))
        assertFalse(EmailDomain.sameDomain("a@eu.example.com", "b@example.com"))
    }

    @Test
    fun `sameDomain returns false when either input is malformed`() {
        assertFalse(EmailDomain.sameDomain("a@example.com", "no-at-sign"))
        assertFalse(EmailDomain.sameDomain("", "b@example.com"))
        assertFalse(EmailDomain.sameDomain(null, "b@example.com"))
    }

    @Test
    fun `isWellFormed enforces single at sign with non-empty parts`() {
        assertTrue(EmailDomain.isWellFormed("a@example.com"))
        assertFalse(EmailDomain.isWellFormed(""))
        assertFalse(EmailDomain.isWellFormed("no-at-sign"))
        assertFalse(EmailDomain.isWellFormed("@nolocal.com"))
        assertFalse(EmailDomain.isWellFormed("trailing@"))
        assertFalse(EmailDomain.isWellFormed("two@at@signs.com"))
        assertFalse(EmailDomain.isWellFormed(null))
    }
}
