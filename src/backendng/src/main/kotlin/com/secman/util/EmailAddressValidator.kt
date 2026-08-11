package com.secman.util

/**
 * The one boundary check for an address SecMan will hand to `InternetAddress.parse`.
 *
 * This is a security control, not input hygiene. The rejected characters are exactly the
 * ones that change the *meaning* of a recipient list or a mail header:
 *
 * - `,` `;` — `InternetAddress.parse` splits on these, so one accepted address would
 *   silently become several recipients.
 * - CR / LF / any whitespace — header injection: an address reaching a `To:` line with a
 *   newline in it can append arbitrary headers.
 * - `:` `<` `>` `"` `\` — address-group and quoted-string syntax, the other way to smuggle
 *   a second recipient past a naive check.
 *
 * It deliberately does *not* try to be RFC 5322 complete. A stricter-than-RFC boundary that
 * cannot be tricked beats a permissive parser that can.
 *
 * Previously this pattern was copy-pasted in `UserMappingBulkImportService` and
 * `UserMappingService`. Two copies of a security control drift; there is now one. Call this
 * rather than writing a third.
 */
object EmailAddressValidator {

    private val PATTERN = Regex("^[^\\s@,;:<>\"\\\\]+@[^\\s@,;:<>\"\\\\]+\\.[^\\s@,;:<>\"\\\\]+$")

    /** Matches the `email` column width across the schema. Longer is rejected, never truncated. */
    const val MAX_LENGTH = 255

    /**
     * True when [address] is safe to use as a single SMTP recipient.
     *
     * Null, blank, over-long and structurally suspicious addresses are all false — callers
     * get one answer to act on rather than a taxonomy.
     */
    fun isValidRecipient(address: String?): Boolean {
        val trimmed = address?.trim() ?: return false
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return false
        return PATTERN.matches(trimmed)
    }

    /**
     * The shape check alone, without the length cap.
     *
     * For callers that report "malformed" and "too long" as distinct errors — per-row import
     * validation does, so the operator is told which of the two a row failed. Prefer
     * [isValidRecipient] anywhere one answer is enough.
     */
    fun matchesPattern(address: String?): Boolean {
        val trimmed = address?.trim() ?: return false
        return trimmed.isNotEmpty() && PATTERN.matches(trimmed)
    }

    /**
     * Make an untrusted string safe to echo into a log line, an error message or assessment
     * notes. Strips CR/LF (log forging, A09) and caps the length so a hostile value cannot
     * flood a log or overflow a column.
     */
    fun sanitizeForEcho(value: String?, maxLength: Int = MAX_LENGTH): String {
        if (value.isNullOrEmpty()) return ""
        return value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .take(maxLength)
    }
}
