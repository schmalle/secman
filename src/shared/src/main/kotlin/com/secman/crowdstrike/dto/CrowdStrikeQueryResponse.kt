package com.secman.crowdstrike.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

/**
 * Response DTO for CrowdStrike vulnerability query
 *
 * Related to:
 * - Feature 015-we-have-currently (CrowdStrike System Vulnerability Lookup)
 * - Feature 041-falcon-instance-lookup (AWS Instance ID queries)
 */
@Serdeable
data class CrowdStrikeQueryResponse(
    /**
     * Echoed hostname from request
     */
    @field:NotBlank
    val hostname: String,

    /**
     * AWS EC2 Instance ID (Feature 041)
     *
     * Populated when querying by instance ID
     * Null for hostname queries
     */
    val instanceId: String? = null,

    /**
     * Number of CrowdStrike devices whose rows are merged into this response
     *
     * Typically 1, 2+ for instance lifecycle transitions or re-imaged/re-enrolled
     * hosts (one hostname, several aids). Populated for both instance-ID (Feature
     * 041) and hostname queries; null only for legacy callers that never set it.
     */
    val deviceCount: Int? = null,

    /**
     * Requested hostnames that could not be resolved to any CrowdStrike device.
     *
     * Only populated by multi-hostname queries (queryServersWithFilters); lets the
     * CLI distinguish "host unknown to Falcon" from "host resolved but has no
     * matching vulnerabilities" — previously both surfaced as count=0.
     */
    val notFoundHostnames: List<String> = emptyList(),

    /**
     * List of vulnerabilities found (empty if none)
     */
    @field:NotNull
    val vulnerabilities: List<CrowdStrikeVulnerabilityDto>,

    /**
     * Total count from CrowdStrike (may exceed list size if limited to 1000)
     */
    @field:NotNull
    val totalCount: Int,

    /**
     * Timestamp when query was executed (ISO 8601)
     */
    @field:NotNull
    val queriedAt: LocalDateTime
)
