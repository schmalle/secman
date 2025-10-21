package com.secman.dto

import com.secman.domain.ExceptionScope
import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * DTO for creating a new vulnerability exception request.
 *
 * Used for POST /api/vulnerability-exception-requests endpoint.
 *
 * Validation rules:
 * - vulnerabilityId: Required, must reference existing vulnerability
 * - scope: Required, SINGLE_VULNERABILITY or CVE_PATTERN
 * - reason: Required, 50-2048 characters, business justification
 * - expirationDate: Required, must be future date
 *
 * Feature: 031-vuln-exception-approval
 * Reference: contracts/exception-request-api.yaml lines 73-92
 */
@Serdeable
data class CreateExceptionRequestDto(
    /**
     * ID of the vulnerability to create exception for
     */
    @field:NotNull(message = "Vulnerability ID is required")
    val vulnerabilityId: Long,

    /**
     * Exception scope: SINGLE_VULNERABILITY or CVE_PATTERN
     * - SINGLE_VULNERABILITY: Applies only to this specific vulnerability on this specific asset
     * - CVE_PATTERN: Applies to all vulnerabilities with this CVE across all assets
     */
    @field:NotNull(message = "Exception scope is required")
    val scope: ExceptionScope,

    /**
     * Business justification for the exception
     * Minimum 50 characters to ensure meaningful justification
     * Maximum 2048 characters for database storage
     */
    @field:NotBlank(message = "Reason is required")
    @field:Size(min = 50, max = 2048, message = "Reason must be between 50 and 2048 characters")
    val reason: String,

    /**
     * When the exception should expire
     * Must be a future date at time of creation
     *
     * Note: Validation uses @Future which checks against current date/time
     * Users will receive warning if expiration > 365 days in future (client-side)
     */
    @field:NotNull(message = "Expiration date is required")
    @field:Future(message = "Expiration date must be in the future")
    val expirationDate: LocalDateTime
)
