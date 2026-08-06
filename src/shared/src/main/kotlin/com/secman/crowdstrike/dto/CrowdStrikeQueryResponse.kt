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
     * Number of CrowdStrike devices found with this instance ID (Feature 041)
     *
     * Typically 1, rarely 2+ (during instance lifecycle transitions)
     * Null for hostname queries
     */
    val deviceCount: Int? = null,

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
