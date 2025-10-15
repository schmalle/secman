package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * DTO representing a single AWS account group with its assets.
 *
 * Used in Account Vulns view to group assets by AWS account ID.
 * Account groups are sorted by awsAccountId (numerical/ascending).
 *
 * @property awsAccountId 12-digit AWS account ID (e.g., "123456789012")
 * @property assets List of assets in this account (sorted by vulnerability count descending)
 * @property totalAssets Number of assets in this account (for group summary header)
 * @property totalVulnerabilities Total vulnerabilities in this account (for group summary header)
 * @property totalCritical Aggregated CRITICAL severity vulnerabilities in this account (nullable for backward compatibility)
 * @property totalHigh Aggregated HIGH severity vulnerabilities in this account (nullable for backward compatibility)
 * @property totalMedium Aggregated MEDIUM severity vulnerabilities in this account (nullable for backward compatibility)
 */
@Serdeable
data class AccountGroupDto(
    val awsAccountId: String,
    val assets: List<AssetVulnCountDto>,
    val totalAssets: Int,
    val totalVulnerabilities: Int,
    
    // Severity aggregation (Feature 019 - nullable for backward compatibility)
    val totalCritical: Int? = null,
    val totalHigh: Int? = null,
    val totalMedium: Int? = null
)
