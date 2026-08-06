package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.secman.dto.Citation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Locks the citation-validation rules: https-only, must have a host, dedup
 * by URL, total serialized payload bounded.
 */
class CitationValidatorTest {

    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val validator = CitationValidator(mapper)

    @Test
    fun `rejects non-https citations`() {
        val cleaned = validator.validate(listOf(
            Citation(title = "ok", url = "https://aws.amazon.com/x"),
            Citation(title = "bad", url = "http://example.com/x"),
            Citation(title = "alsobad", url = "ftp://example.com/x")
        ))
        assertEquals(1, cleaned.size)
        assertEquals("https://aws.amazon.com/x", cleaned.single().url)
    }

    @Test
    fun `rejects hostless and malformed URLs`() {
        val cleaned = validator.validate(listOf(
            Citation(url = "https:///oops"),       // no host
            Citation(url = "not-a-url"),
            Citation(url = "https://"),
            Citation(url = "")
        ))
        assertTrue(cleaned.isEmpty())
    }

    @Test
    fun `deduplicates citations by URL (case-insensitive)`() {
        val cleaned = validator.validate(listOf(
            Citation(url = "https://iso.org/standard/X.html"),
            Citation(url = "HTTPS://ISO.ORG/standard/X.html")   // dup
        ))
        assertEquals(1, cleaned.size)
    }

    @Test
    fun `truncates oversized snippets`() {
        val long = "x".repeat(500)
        val cleaned = validator.validate(listOf(Citation(url = "https://aws.amazon.com/y", snippet = long)))
        assertEquals(1, cleaned.size)
        assertTrue((cleaned.single().snippet?.length ?: 0) <= 201, "snippet was not truncated")
    }

    @Test
    fun `trims the trailing entries when the serialised payload exceeds budget`() {
        // 50 entries, each non-trivial — should exceed 2KiB and get trimmed.
        val many = (1..50).map { Citation(title = "t$it", url = "https://example.com/$it", snippet = "snippet $it for budget overflow check") }
        val cleaned = validator.validate(many)
        assertTrue(cleaned.size < many.size, "validator did not trim oversize input (kept ${cleaned.size})")
        assertTrue(mapper.writeValueAsBytes(cleaned).size <= 2 * 1024)
    }
}
