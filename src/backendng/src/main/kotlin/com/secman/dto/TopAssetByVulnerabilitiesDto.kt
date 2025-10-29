package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * DTO for top assets ranked by vulnerability count
 *
 * Represents an asset with its vulnerability statistics, used for identifying
 * assets with the highest vulnerability burden. Includes severity breakdown.
 *
 * Feature: 036-vuln-stats-lense
 * Task: T031 [US3]
 * Spec reference: spec.md FR-005, FR-006
 * User Story: US3 - View Asset Vulnerability Statistics (P3)
 * Data model: data-model.md Section "TopAssetByVulnerabilitiesDto"
 */
@Serdeable
data class TopAssetByVulnerabilitiesDto(
    /**
     * Asset ID for navigation and linking
     */
    val assetId: Long,

    /**
     * Asset name (hostname, IP, or descriptive name)
     */
    val assetName: String,

    /**
     * Asset type (e.g., "Server", "Workstation", "Network Device")
     * May be null if not specified
     */
    val assetType: String?,

    /**
     * Asset IP address
     * May be null if not specified
     */
    val assetIp: String?,

    /**
     * Total vulnerability count across all severity levels
     */
    val totalVulnerabilityCount: Long,

    /**
     * Count of CRITICAL severity vulnerabilities
     */
    val criticalCount: Long,

    /**
     * Count of HIGH severity vulnerabilities
     */
    val highCount: Long,

    /**
     * Count of MEDIUM severity vulnerabilities
     */
    val mediumCount: Long,

    /**
     * Count of LOW severity vulnerabilities
     */
    val lowCount: Long
)
