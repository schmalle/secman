package com.secman.crowdstrike.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotBlank

/**
 * DTO for CrowdStrike Falcon API configuration
 * Represents OAuth2 credentials and API endpoints
 */
@Serdeable
data class FalconConfigDto(
    /**
     * OAuth2 client ID
     */
    @field:NotBlank
    val clientId: String,

    /**
     * OAuth2 client secret
     */
    @field:NotBlank
    val clientSecret: String,

    /**
     * CrowdStrike API base URL (e.g., https://api.crowdstrike.com)
     */
    @field:NotBlank
    val baseUrl: String = "https://api.crowdstrike.com",

    /**
     * Configuration name/description
     */
    val name: String? = null
)
