package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Request DTO for saving CrowdStrike vulnerabilities to database
 *
 * - Feature 015-we-have-currently (CrowdStrike System Vulnerability Lookup)
 * - Feature 041-falcon-instance-lookup (AWS Instance ID Support)
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
    @field:Valid
    @field:Size(max = 50000)
    val vulnerabilities: List<CrowdStrikeVulnerabilityDto> = emptyList(),

    /**
     * AWS EC2 Instance ID (optional, for instance ID queries)
     *
     * When present, this value will be stored in the asset's cloudInstanceId field
     * for asset enrichment and tracking purposes.
     */
    @field:Size(max = 19, message = "Instance ID must not exceed 19 characters")
    val instanceId: String? = null
)
