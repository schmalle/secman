package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Response DTO for CVE lookup from NVD API
 *
 * Feature: 072-cve-link-lookup
 */
@Serdeable
data class CveLookupDto(
    val cveId: String,
    val description: String?,
    val severity: String?,
    val cvssScore: Double?,
    val publishedDate: String?,
    val references: List<String> = emptyList()
)
