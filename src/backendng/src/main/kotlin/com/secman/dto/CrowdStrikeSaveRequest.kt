package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

/**
 * Request DTO for saving CrowdStrike vulnerabilities to database
 *
 * Related to: Feature 015-we-have-currently (CrowdStrike System Vulnerability Lookup)
 */
@Serdeable
data class CrowdStrikeSaveRequest(
    /**
     * System hostname (used for asset matching/creation)
     */
    @field:NotBlank(message = "Hostname is required")
    @field:Size(min = 1, max = 255, message = "Hostname must be between 1 and 255 characters")
    val hostname: String,

    /**
     * List of vulnerabilities to save (at least one required)
     */
    @field:NotEmpty(message = "At least one vulnerability is required")
    @field:Valid
    val vulnerabilities: List<CrowdStrikeVulnerabilityDto>
)
