package com.secman.util

/**
 * Single source of truth for parsing user-typed email addresses and
 * comparing their domains. Used by AWS-sharing invite validation;
 * intentionally narrow so all email-domain decisions go through one
 * implementation.
 *
 * "Well-formed" here means: exactly one '@', non-empty local part,
 * non-empty domain part. We deliberately do NOT enforce full RFC 5322
 * — the cost is high and the value is low, given that storage already
 * accepts whatever the user typed and login goes through OAuth which
 * applies its own validation.
 */
object EmailDomain {

    /**
     * Returns the lowercase domain portion of [raw] (the substring after
     * the single '@'), or null if [raw] is not well-formed.
     */
    fun extractDomain(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (!isWellFormed(trimmed)) return null
        return trimmed.substringAfter('@').lowercase()
    }

    /**
     * Returns true when both inputs are well-formed and have equal
     * domains (case-insensitive). Subdomains are treated as different
     * domains: example.com != eu.example.com.
     */
    fun sameDomain(a: String?, b: String?): Boolean {
        val da = extractDomain(a) ?: return false
        val db = extractDomain(b) ?: return false
        return da == db
    }

    /**
     * Quick well-formedness check: exactly one '@', non-empty local
     * part, non-empty domain part. Whitespace is trimmed first.
     */
    fun isWellFormed(raw: String?): Boolean {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return false
        val atCount = s.count { it == '@' }
        if (atCount != 1) return false
        val local = s.substringBefore('@')
        val domain = s.substringAfter('@')
        return local.isNotEmpty() && domain.isNotEmpty()
    }
}
