package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * Response DTO for saving CrowdStrike vulnerabilities to database
 *
 * Related to: Feature 015-we-have-currently (CrowdStrike System Vulnerability Lookup)
 */
@Serdeable
data class CrowdStrikeSaveResponse(
    /**
     * Human-readable summary of save operation
     */
    @field:NotBlank
    val message: String,

    /**
     * Count of Vulnerability records created
     */
    @field:NotNull
    val vulnerabilitiesSaved: Int,

    /**
     * Count of new Asset records created (0 if asset existed)
     */
    @field:NotNull
    val assetsCreated: Int,

    /**
     * List of errors encountered during save (if any)
     */
    @field:NotNull
    val errors: List<String> = emptyList()
)
