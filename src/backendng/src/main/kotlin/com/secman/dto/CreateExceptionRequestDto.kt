package com.secman.dto

import com.secman.domain.VulnerabilityException
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
 * Two-axis model (Feature 196): subject × scope.
 *
 * Validation rules:
 * - vulnerabilityId: Required, must reference existing vulnerability
 * - subject: Required (ALL_VULNS, PRODUCT, CVE)
 * - scope: Required (GLOBAL, IP, ASSET, AWS_ACCOUNT)
 * - subjectValue: Required for PRODUCT/CVE; must be null for ALL_VULNS
 * - scopeValue: Required for IP/AWS_ACCOUNT; must be null for GLOBAL/ASSET
 * - assetId: Required for scope=ASSET; null otherwise
 * - reason: Required, 50-2048 characters, business justification
 * - expirationDate: Required, must be future date
 *
 * Forbidden combination: subject=ALL_VULNS && scope=GLOBAL.
 * subject=ALL_VULNS requires ADMIN or VULN role (enforced server-side).
 */
@Serdeable
data class CreateExceptionRequestDto(
    /**
     * ID of the vulnerability to create exception for.
     */
    @field:NotNull(message = "Vulnerability ID is required")
    val vulnerabilityId: Long,

    /**
     * WHAT is excepted: ALL_VULNS, PRODUCT, or CVE.
     */
    @field:NotNull(message = "Exception subject is required")
    val subject: VulnerabilityException.Subject,

    /**
     * WHERE the exception applies: GLOBAL, IP, ASSET, or AWS_ACCOUNT.
     */
    @field:NotNull(message = "Exception scope is required")
    val scope: VulnerabilityException.Scope,

    /**
     * Subject value: product name pattern or comma-separated CVE list.
     * Must be null when subject=ALL_VULNS.
     */
    @field:Size(max = 512)
    val subjectValue: String? = null,

    /**
     * Scope value: IP address (scope=IP) or AWS account ID (scope=AWS_ACCOUNT).
     * Must be null for scope=GLOBAL or scope=ASSET.
     */
    @field:Size(max = 255)
    val scopeValue: String? = null,

    /**
     * Asset ID. Required when scope=ASSET; null otherwise.
     */
    val assetId: Long? = null,

    /**
     * Business justification for the exception.
     */
    @field:NotBlank(message = "Reason is required")
    @field:Size(min = 50, max = 2048, message = "Reason must be between 50 and 2048 characters")
    val reason: String,

    /**
     * When the exception should expire.
     */
    @field:NotNull(message = "Expiration date is required")
    @field:Future(message = "Expiration date must be in the future")
    val expirationDate: LocalDateTime
)
