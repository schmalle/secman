package com.secman.dto

import com.secman.domain.ComplianceStatus
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

/**
 * DTOs for the Asset Compliance History feature.
 * Feature: ec2-vulnerability-tracking
 */

@Serdeable
data class AssetComplianceOverviewDto(
    val assetId: Long,
    val assetName: String,
    val assetType: String?,
    val cloudInstanceId: String?,
    val currentStatus: ComplianceStatus,
    val lastChangeAt: LocalDateTime,
    val overdueCount: Int,
    val oldestVulnDays: Int?,
    val source: String
)

@Serdeable
data class AssetComplianceHistoryDto(
    val id: Long,
    val status: ComplianceStatus,
    val changedAt: LocalDateTime,
    val overdueCount: Int,
    val oldestVulnDays: Int?,
    val source: String,
    val durationDays: Int
)

@Serdeable
data class AssetComplianceSummaryDto(
    val totalAssets: Long,
    val compliantCount: Long,
    val nonCompliantCount: Long,
    val neverAssessedCount: Long,
    val compliancePercentage: Double
)
