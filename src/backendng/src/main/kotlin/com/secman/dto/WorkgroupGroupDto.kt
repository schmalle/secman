package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * DTO representing a single workgroup with its assets and vulnerability counts.
 *
 * Used in WG Vulns view to group assets by workgroup.
 * Workgroup groups are sorted alphabetically by workgroup name.
 *
 * Feature: 022-wg-vulns-handling - Workgroup-Based Vulnerability View
 *
 * @property workgroupId Unique workgroup identifier
 * @property workgroupName Workgroup name (1-100 characters)
 * @property workgroupDescription Optional workgroup description (max 512 characters)
 * @property assets List of assets in this workgroup (sorted by vulnerability count descending)
 * @property totalAssets Number of assets in this workgroup (for group summary header)
 * @property totalVulnerabilities Total vulnerabilities in this workgroup (for group summary header)
 * @property totalCritical Aggregated CRITICAL severity vulnerabilities in this workgroup (nullable for backward compatibility)
 * @property totalHigh Aggregated HIGH severity vulnerabilities in this workgroup (nullable for backward compatibility)
 * @property totalMedium Aggregated MEDIUM severity vulnerabilities in this workgroup (nullable for backward compatibility)
 */
@Serdeable
data class WorkgroupGroupDto(
    val workgroupId: Long,
    val workgroupName: String,
    val workgroupDescription: String? = null,
    val assets: List<AssetVulnCountDto>,
    val totalAssets: Int,
    val totalVulnerabilities: Int,
    
    // Severity aggregation (nullable for backward compatibility)
    val totalCritical: Int? = null,
    val totalHigh: Int? = null,
    val totalMedium: Int? = null
)
