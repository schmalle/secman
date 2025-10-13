package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Request DTO for querying CrowdStrike vulnerabilities by hostname
 *
 * Related to: Feature 015-we-have-currently (CrowdStrike System Vulnerability Lookup)
 */
@Serdeable
data class CrowdStrikeQueryRequest(
    /**
     * System hostname to query (case-sensitive as it appears in CrowdStrike)
     */
    @field:NotBlank(message = "Hostname cannot be blank")
    @field:Size(min = 1, max = 255, message = "Hostname must be between 1 and 255 characters")
    val hostname: String
)
