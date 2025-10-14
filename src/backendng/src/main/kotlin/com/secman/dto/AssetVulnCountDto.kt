package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * DTO representing a single asset with its vulnerability count.
 *
 * Used in Account Vulns view to display assets grouped by AWS account.
 *
 * @property id Asset ID (used for navigation to asset detail page)
 * @property name Asset name (displayed in table, clickable link)
 * @property type Asset type (e.g., SERVER, WORKSTATION)
 * @property vulnerabilityCount Number of vulnerabilities for this asset (0 if none)
 */
@Serdeable
data class AssetVulnCountDto(
    val id: Long,
    val name: String,
    val type: String,
    val vulnerabilityCount: Int
)
