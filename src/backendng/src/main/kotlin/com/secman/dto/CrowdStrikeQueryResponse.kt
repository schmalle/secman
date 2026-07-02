package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * Response DTO for CrowdStrike vulnerability query
 *
 * Related to:
 * - Feature 015-we-have-currently (CrowdStrike System Vulnerability Lookup)
 * - Feature 041-falcon-instance-lookup (AWS Instance ID queries)
 */
@Serdeable
data class CrowdStrikeQueryResponse(
    /**
     * Echoed hostname from request
     */
    @field:NotBlank
    val hostname: String,

    /**
     * AWS EC2 Instance ID (Feature 041)
     *
     * Populated when querying by instance ID
     * Null for hostname queries
     */
    val instanceId: String? = null,

    /**
     * Number of CrowdStrike devices found with this instance ID (Feature 041)
     *
     * Typically 1, rarely 2+ (during instance lifecycle transitions)
     * Null for hostname queries
     */
    val deviceCount: Int? = null,

    /**
     * AWS account ID associated with the asset (from cloudAccountId field).
     * Null if not available (e.g., non-AWS assets or CrowdStrike API queries).
     */
    val cloudAccountId: String? = null,

    /**
     * List of vulnerabilities found (empty if none)
     */
    @field:NotNull
    val vulnerabilities: List<CrowdStrikeVulnerabilityDto>,

    /**
     * Total count from CrowdStrike (may exceed list size if limited to 1000)
     */
    @field:NotNull
    val totalCount: Int,

    /**
     * Timestamp when the underlying data was last touched (ISO-8601, UTC, trailing `Z`).
     *
     * Emitted as an explicit UTC instant string (e.g. `2026-07-02T04:14:18.246830Z`) rather than a
     * zoneless `LocalDateTime`, so that `new Date(queriedAt)` on the client parses identically across
     * browser JS engines (V8/Edge vs JavaScriptCore/Safari). A zoneless value was parsed as
     * browser-local, making the freshness/auto-refresh math diverge by browser. See
     * `src/frontend/src/utils/cacheUtils.ts` and `parseServerDate`.
     */
    @field:NotBlank
    val queriedAt: String,

    /**
     * Where these rows came from:
     * - `"DATABASE"`  — served from the persisted `vulnerability` table (consistent with
     *   the Current/Account Vulnerabilities views).
     * - `"LIVE_API"`  — the asset had no persisted rows, so the service fell through to the
     *   live CrowdStrike Falcon API. These rows are NOT yet imported and will therefore NOT
     *   appear in the persisted-table views. Lets the UI label the difference instead of
     *   silently showing live counts that contradict the other pages.
     */
    val dataSource: String = "DATABASE"
)
