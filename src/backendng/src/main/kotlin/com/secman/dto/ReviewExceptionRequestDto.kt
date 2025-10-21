package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.Size

/**
 * DTO for reviewing (approving or rejecting) an exception request.
 *
 * Used for:
 * - POST /api/vulnerability-exception-requests/{id}/approve
 * - POST /api/vulnerability-exception-requests/{id}/reject
 *
 * Validation rules:
 * - reviewComment: Optional for approval, REQUIRED for rejection
 * - When provided: 10-1024 characters
 *
 * Note: Validation enforcement for "required on rejection" is handled in service layer
 * because it's context-dependent (approve vs reject action).
 *
 * Feature: 031-vuln-exception-approval
 * Reference: contracts/exception-request-api.yaml lines 627-637
 */
@Serdeable
data class ReviewExceptionRequestDto(
    /**
     * Reviewer's comment/justification
     *
     * Approval: Optional (can explain why approved or leave blank)
     * Rejection: REQUIRED (must explain why rejected, minimum 10 characters)
     *
     * Maximum 1024 characters for database storage
     */
    @field:Size(min = 10, max = 1024, message = "Review comment must be between 10 and 1024 characters when provided")
    val reviewComment: String? = null
)
