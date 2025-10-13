package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

/**
 * Response DTO for CrowdStrike vulnerability query
 *
 * Related to: Feature 015-we-have-currently (CrowdStrike System Vulnerability Lookup)
 */
@Serdeable
data class CrowdStrikeQueryResponse(
    /**
     * Echoed hostname from request
     */
    @field:NotBlank
    val hostname: String,

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
