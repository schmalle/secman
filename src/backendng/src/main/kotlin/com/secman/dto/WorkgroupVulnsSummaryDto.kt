package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * DTO representing the top-level response for WG Vulns view.
 *
 * Contains all workgroup groups with their assets, plus overall summary statistics.
 * This is the response body for GET /api/wg-vulns endpoint.
 *
 * Feature: 022-wg-vulns-handling - Workgroup-Based Vulnerability View
 *
 * @property workgroupGroups List of workgroup groups with their assets (sorted alphabetically by workgroup name)
 * @property totalAssets Total number of assets across all workgroups (for summary display)
 * @property totalVulnerabilities Total number of vulnerabilities across all assets (for summary display)
 * @property globalCritical Total CRITICAL severity vulnerabilities across all workgroups (nullable for backward compatibility)
 * @property globalHigh Total HIGH severity vulnerabilities across all workgroups (nullable for backward compatibility)
 * @property globalMedium Total MEDIUM severity vulnerabilities across all workgroups (nullable for backward compatibility)
 */
@Serdeable
data class WorkgroupVulnsSummaryDto(
    val workgroupGroups: List<WorkgroupGroupDto>,
    val totalAssets: Int,
    val totalVulnerabilities: Int,
    
    // Global severity totals (nullable for backward compatibility)
    val globalCritical: Int? = null,
    val globalHigh: Int? = null,
    val globalMedium: Int? = null,

    // Metadata about the latest CrowdStrike import (nullable for backward compatibility)
    val lastImport: CrowdStrikeImportStatusDto? = null
)
