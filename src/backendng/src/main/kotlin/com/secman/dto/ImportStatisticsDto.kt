package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Response DTO for /api/crowdstrike/vulnerabilities/save endpoint
 *
 * Feature: 032-servers-query-import
 * Feature: 043-crowdstrike-domain-import (added uniqueDomainCount, discoveredDomains)
 * Spec reference: FR-015c
 *
 * Contains import statistics including server counts, vulnerability counts,
 * domain discovery statistics, and error messages for failed imports.
 *
 * @property serversProcessed Total servers in request (created + updated + errors)
 * @property serversCreated New Asset records created
 * @property serversUpdated Existing Asset records reused/updated
 * @property vulnerabilitiesImported Total Vulnerability records created (across all servers)
 * @property vulnerabilitiesSkipped Count of vulnerabilities without CVE ID (filtered before import)
 * @property vulnerabilitiesWithPatchDate Count of imported vulnerabilities that have patch publication date set
 * @property uniqueDomainCount Number of unique Active Directory domains discovered (Feature 043)
 * @property discoveredDomains List of unique domain names discovered, sorted alphabetically (Feature 043)
 * @property errors List of error messages for failed server imports (transaction rollbacks)
 */
@Serdeable
data class ImportStatisticsDto(
    val serversProcessed: Int,
    val serversCreated: Int,
    val serversUpdated: Int,
    val vulnerabilitiesImported: Int,
    val vulnerabilitiesSkipped: Int,
    val vulnerabilitiesWithPatchDate: Int,
    val uniqueDomainCount: Int = 0,
    val discoveredDomains: List<String> = emptyList(),
    val errors: List<String>
)
