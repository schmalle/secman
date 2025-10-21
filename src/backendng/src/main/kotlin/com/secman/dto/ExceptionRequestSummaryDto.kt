package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * DTO for exception request summary statistics.
 *
 * Used for dashboard summary cards:
 * - GET /api/vulnerability-exception-requests/my/summary (user dashboard)
 * - Dashboard statistics section (approval dashboard)
 *
 * Provides counts by status for "at-a-glance" view of request distribution.
 *
 * Feature: 031-vuln-exception-approval (FR-011, FR-019)
 * Reference: spec.md User Story 7 (US7), tasks.md TASK-012
 */
@Serdeable
data class ExceptionRequestSummaryDto(
    /**
     * Total number of exception requests (all statuses)
     */
    val totalRequests: Long,

    /**
     * Number of approved requests
     * Displayed with green badge in UI
     */
    val approvedCount: Long,

    /**
     * Number of pending requests (awaiting approval)
     * Displayed with yellow badge in UI
     */
    val pendingCount: Long,

    /**
     * Number of rejected requests
     * Displayed with red badge in UI
     */
    val rejectedCount: Long,

    /**
     * Number of expired requests (past expiration date)
     * Displayed with gray badge in UI
     */
    val expiredCount: Long,

    /**
     * Number of cancelled requests (cancelled by requester)
     * Displayed with gray badge in UI
     */
    val cancelledCount: Long
)
