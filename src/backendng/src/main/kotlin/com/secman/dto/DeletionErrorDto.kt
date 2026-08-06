package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Structured error response for failed deletions
 * Feature: 033-cascade-asset-deletion (User Story 1 & 3 - Error Handling)
 *
 * Purpose: Response for DELETE endpoints when deletion fails
 * Provides detailed, actionable error information to users
 *
 * Related Requirements:
 * - FR-013: System MUST provide detailed structured error messages
 * - FR-011: Pessimistic locking error handling (LOCKED error type)
 * - FR-012: Timeout warning error handling (TIMEOUT_WARNING error type)
 * - Contract: contracts/cascade-delete-api.yaml
 */
@Serdeable
data class DeletionErrorDto(
    val errorType: DeletionErrorType,
    val assetId: Long,
    val assetName: String,
    val cause: String,
    val suggestedAction: String,
    val technicalDetails: String? = null
)

/**
 * Types of deletion errors
 */
enum class DeletionErrorType {
    /**
     * Asset is currently locked by another deletion operation
     * HTTP 409 Conflict
     */
    LOCKED,

    /**
     * Deletion would exceed 60-second transaction timeout
     * HTTP 422 Unprocessable Entity
     */
    TIMEOUT_WARNING,

    /**
     * Transaction timeout occurred during deletion
     * HTTP 500 Internal Server Error
     */
    TIMEOUT,

    /**
     * Database constraint violation
     * HTTP 500 Internal Server Error
     */
    CONSTRAINT_VIOLATION,

    /**
     * Generic internal error
     * HTTP 500 Internal Server Error
     */
    INTERNAL_ERROR
}
