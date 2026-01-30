package com.secman.service

import com.secman.dto.CveLookupDto
import io.micronaut.cache.annotation.Cacheable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Service for looking up CVE details from NVD API v2.0
 *
 * Proxies requests to NVD to avoid CORS issues and centralize caching.
 * Responses are cached for 24h via Micronaut's Caffeine cache.
 *
 * Feature: 072-cve-link-lookup
 */
@Singleton
open class CveLookupService {

    private val logger = LoggerFactory.getLogger(CveLookupService::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val cveIdPattern = Regex("^CVE-\\d{4}-\\d{4,}\$")

    /**
     * Look up CVE details from NVD API v2.0
     *
     * @param cveId CVE identifier (e.g., "CVE-2023-12345")
     * @return CveLookupDto with CVE details, or null if not found or invalid
     */
    @Cacheable("cve_descriptions")
    open fun lookupCve(cveId: String): CveLookupDto? {
        if (!cveIdPattern.matches(cveId)) {
            logger.debug("Invalid CVE ID format: {}", cveId)
            return null
        }

        return try {
            val url = "https://services.nvd.nist.gov/rest/json/cves/2.0?cveId=$cveId"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != 200) {
                logger.warn("NVD API returned status {} for CVE {}", response.statusCode(), cveId)
                return null
            }

            parseNvdResponse(cveId, response.body())
        } catch (e: Exception) {
            logger.error("Error looking up CVE {}: {}", cveId, e.message)
            null
        }
    }

    private fun parseNvdResponse(cveId: String, responseBody: String): CveLookupDto? {
        return try {
            // Simple JSON parsing without Jackson dependency - parse NVD v2.0 response
            val body = responseBody

            // Extract description (English)
            val description = extractEnglishDescription(body)

            // Extract CVSS score and severity from v31 or v2
            val cvssScore = extractCvssScore(body)
            val severity = extractSeverity(body)

            // Extract published date
            val publishedDate = extractField(body, "published")

            // Extract references (first 5)
            val references = extractReferences(body)

            CveLookupDto(
                cveId = cveId,
                description = description,
                severity = severity,
                cvssScore = cvssScore,
                publishedDate = publishedDate,
                references = references
            )
        } catch (e: Exception) {
            logger.error("Error parsing NVD response for CVE {}: {}", cveId, e.message)
            null
        }
    }

    private fun extractEnglishDescription(json: String): String? {
        // Find descriptions array and extract English description
        val descriptionsIdx = json.indexOf("\"descriptions\"")
        if (descriptionsIdx == -1) return null

        // Find English description value
        val langEnIdx = json.indexOf("\"lang\":\"en\"", descriptionsIdx)
        if (langEnIdx == -1) return null

        // Look for "value" near this lang entry (within the same object)
        val valueIdx = json.indexOf("\"value\":\"", langEnIdx - 200)
        if (valueIdx == -1 || valueIdx > langEnIdx + 200) {
            // Try looking after lang
            val valueAfterIdx = json.indexOf("\"value\":\"", langEnIdx)
            if (valueAfterIdx == -1 || valueAfterIdx > langEnIdx + 200) return null
            return extractJsonStringValue(json, valueAfterIdx)
        }
        return extractJsonStringValue(json, valueIdx)
    }

    private fun extractJsonStringValue(json: String, valueKeyIdx: Int): String? {
        val valueStart = json.indexOf("\"value\":\"", valueKeyIdx)
        if (valueStart == -1) return null
        val contentStart = valueStart + "\"value\":\"".length
        val contentEnd = findClosingQuote(json, contentStart)
        if (contentEnd == -1) return null
        return json.substring(contentStart, contentEnd).replace("\\\"", "\"").replace("\\n", " ")
    }

    private fun findClosingQuote(json: String, startIdx: Int): Int {
        var i = startIdx
        while (i < json.length) {
            if (json[i] == '"' && (i == 0 || json[i - 1] != '\\')) {
                return i
            }
            i++
        }
        return -1
    }

    private fun extractCvssScore(json: String): Double? {
        // Try CVSS v3.1 first, then v3.0, then v2
        for (key in listOf("\"cvssMetricV31\"", "\"cvssMetricV30\"", "\"cvssMetricV2\"")) {
            val idx = json.indexOf(key)
            if (idx != -1) {
                val scoreIdx = json.indexOf("\"baseScore\":", idx)
                if (scoreIdx != -1 && scoreIdx < idx + 500) {
                    val numStart = scoreIdx + "\"baseScore\":".length
                    val numEnd = json.indexOfAny(charArrayOf(',', '}', ' '), numStart)
                    if (numEnd != -1) {
                        return json.substring(numStart, numEnd).trim().toDoubleOrNull()
                    }
                }
            }
        }
        return null
    }

    private fun extractSeverity(json: String): String? {
        // Try CVSS v3.1 first, then v3.0
        for (key in listOf("\"cvssMetricV31\"", "\"cvssMetricV30\"")) {
            val idx = json.indexOf(key)
            if (idx != -1) {
                val severityIdx = json.indexOf("\"baseSeverity\":\"", idx)
                if (severityIdx != -1 && severityIdx < idx + 500) {
                    val start = severityIdx + "\"baseSeverity\":\"".length
                    val end = json.indexOf('"', start)
                    if (end != -1) {
                        return json.substring(start, end)
                    }
                }
            }
        }
        return null
    }

    private fun extractField(json: String, fieldName: String): String? {
        val key = "\"$fieldName\":\""
        val idx = json.indexOf(key)
        if (idx == -1) return null
        val start = idx + key.length
        val end = json.indexOf('"', start)
        if (end == -1) return null
        return json.substring(start, end)
    }

    private fun extractReferences(json: String): List<String> {
        val refs = mutableListOf<String>()
        val refsIdx = json.indexOf("\"references\"")
        if (refsIdx == -1) return refs

        var searchFrom = refsIdx
        while (refs.size < 5) {
            val urlIdx = json.indexOf("\"url\":\"", searchFrom)
            if (urlIdx == -1 || urlIdx > refsIdx + 5000) break
            val start = urlIdx + "\"url\":\"".length
            val end = json.indexOf('"', start)
            if (end == -1) break
            refs.add(json.substring(start, end))
            searchFrom = end + 1
        }
        return refs
    }
}
