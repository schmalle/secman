package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.dto.Citation
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Rejects unsafe / unhelpful citations from the LLM:
 *   * URL must parse and use `https`.
 *   * URL must have a host (no relative/file/data URLs).
 *   * Deduplicate by URL (case-insensitive).
 *   * Total JSON-serialized size of the surviving list must be ≤ 2 KiB.
 *
 * Snippets longer than 200 chars are truncated rather than dropped.
 */
@Singleton
class CitationValidator(private val objectMapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(CitationValidator::class.java)

    private companion object {
        const val MAX_TOTAL_JSON_BYTES = 2 * 1024
        const val MAX_SNIPPET_CHARS = 200
    }

    fun validate(raw: List<Citation>): List<Citation> {
        if (raw.isEmpty()) return emptyList()

        val seen = HashSet<String>()
        val cleaned = mutableListOf<Citation>()

        for (c in raw) {
            val url = c.url.trim()
            if (url.isEmpty()) continue
            val parsed = try { URI(url) } catch (e: Exception) {
                log.debug("Discarding malformed citation URL: {}", url.take(80))
                continue
            }
            if (parsed.scheme?.lowercase() != "https") {
                log.debug("Discarding non-https citation: {}", url.take(80))
                continue
            }
            if (parsed.host.isNullOrBlank()) {
                log.debug("Discarding hostless citation: {}", url.take(80))
                continue
            }
            val key = url.lowercase()
            if (!seen.add(key)) continue

            val snippet = c.snippet?.let { if (it.length > MAX_SNIPPET_CHARS) it.take(MAX_SNIPPET_CHARS) + "…" else it }
            cleaned += c.copy(url = url, snippet = snippet)
        }

        // Trim until the serialized payload fits the budget.
        while (cleaned.isNotEmpty() && objectMapper.writeValueAsBytes(cleaned).size > MAX_TOTAL_JSON_BYTES) {
            cleaned.removeAt(cleaned.size - 1)
        }
        return cleaned
    }
}
