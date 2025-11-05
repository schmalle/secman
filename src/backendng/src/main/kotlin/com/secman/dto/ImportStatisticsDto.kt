package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Response DTO for /api/crowdstrike/vulnerabilities/save endpoint
 *
 * Feature: 032-servers-query-import
 * Spec reference: FR-015c
 *
 * Contains import statistics including server counts, vulnerability counts,
 * and error messages for failed imports.
 *
 * @property serversProcessed Total servers in request (created + updated + errors)
 * @property serversCreated New Asset records created
 * @property serversUpdated Existing Asset records reused/updated
 * @property vulnerabilitiesImported Total Vulnerability records created (across all servers)
 * @property vulnerabilitiesSkipped Count of vulnerabilities without CVE ID (filtered before import)
 * @property vulnerabilitiesWithPatchDate Count of imported vulnerabilities that have patch publication date set
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
    val errors: List<String>
)
