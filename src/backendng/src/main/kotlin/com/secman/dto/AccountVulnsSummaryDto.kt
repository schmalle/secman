package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * DTO representing the top-level response for Account Vulns view.
 *
 * Contains all account groups with their assets, plus overall summary statistics.
 * This is the response body for GET /api/account-vulns endpoint.
 *
 * @property accountGroups List of AWS account groups with their assets (sorted by account ID ascending)
 * @property totalAssets Total number of assets across all accounts (for summary display)
 * @property totalVulnerabilities Total number of vulnerabilities across all assets (for summary display)
 */
@Serdeable
data class AccountVulnsSummaryDto(
    val accountGroups: List<AccountGroupDto>,
    val totalAssets: Int,
    val totalVulnerabilities: Int
)
