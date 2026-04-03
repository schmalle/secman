package com.secman.util

/**
 * Utility to prevent Excel formula injection (CSV injection / DDE attacks).
 *
 * When user-controlled data is written to Excel cells, an attacker can craft
 * values starting with =, +, -, @, \t, or \r that Excel interprets as formulas.
 * For example: =cmd|'/c calc'!A1 or =HYPERLINK("http://evil.com","Click")
 *
 * This sanitizer prefixes dangerous values with a single quote ('), which forces
 * Excel to treat the cell as a text literal.
 */
object ExcelSanitizer {

    private val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /**
     * Sanitize a string value before writing it to an Excel cell.
     * Returns the original value if safe, or prefixed with ' to neutralize formulas.
     */
    fun sanitize(value: String?): String {
        if (value.isNullOrEmpty()) return value ?: ""
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed[0] in FORMULA_PREFIXES) {
            "'$trimmed"
        } else {
            trimmed
        }
    }
}
