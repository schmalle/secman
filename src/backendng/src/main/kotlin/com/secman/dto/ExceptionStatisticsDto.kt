package com.secman.dto

import com.secman.domain.ExceptionRequestStatus
import io.micronaut.serde.annotation.Serdeable

/**
 * DTO for exception request statistics response.
 *
 * Provides comprehensive metrics for governance and reporting:
 * - Total requests in date range
 * - Approval rate percentage
 * - Average (median) approval time in hours
 * - Request counts by status
 * - Top requesters with counts
 * - Top CVEs with counts
 *
 * Feature: 031-vuln-exception-approval
 * User Story 8: Analytics & Reporting (P3)
 * Phase 11: Analytics & Reporting
 * Reference: spec.md acceptance scenarios US8-1, US8-2
 */
@Serdeable
data class ExceptionStatisticsDto(
    /**
     * Total number of requests in the selected date range.
     */
    val totalRequests: Long,

    /**
     * Approval rate as a percentage (0.0-100.0).
     *
     * Formula: (APPROVED count / (APPROVED + REJECTED)) * 100
     * Null if no reviewed requests exist.
     */
    val approvalRatePercent: Double?,

    /**
     * Average (median) approval time in hours.
     *
     * Calculated from created_at to review_date for APPROVED requests.
     * Uses median (not mean) to avoid outlier skew.
     * Null if no approved requests exist.
     */
    val averageApprovalTimeHours: Double?,

    /**
     * Count of requests by status.
     *
     * Map of status enum name → count.
     * Example: {"PENDING": 5, "APPROVED": 10, "REJECTED": 2}
     */
    val requestsByStatus: Map<String, Long>,

    /**
     * Top requesters by request count.
     *
     * List of username → count pairs, sorted descending by count.
     * Limited to top 10.
     */
    val topRequesters: List<TopRequesterDto>,

    /**
     * Top CVEs by request count.
     *
     * List of CVE ID → count pairs, sorted descending by count.
     * Limited to top 10.
     */
    val topCVEs: List<TopCVEDto>
)

/**
 * DTO for top requester entry.
 */
@Serdeable
data class TopRequesterDto(
    /**
     * Username of the requester.
     */
    val username: String,

    /**
     * Number of requests submitted by this user.
     */
    val count: Long
)

/**
 * DTO for top CVE entry.
 */
@Serdeable
data class TopCVEDto(
    /**
     * CVE identifier (e.g., "CVE-2023-12345").
     */
    val cveId: String,

    /**
     * Number of exception requests for this CVE.
     */
    val count: Long
)
