package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

/**
 * DTO for outdated asset response
 *
 * Maps OutdatedAssetMaterializedView to API response format
 * per contract specification (contracts/01-get-outdated-assets.md)
 *
 * Feature: 034-outdated-assets
 * Task: T023
 * User Story: US1 - View Outdated Assets (P1)
 * Spec reference: contracts/01-get-outdated-assets.md
 */
@Serdeable
data class OutdatedAssetDto(
    /** Materialized view record ID */
    val id: Long,

    /** Asset ID from assets table */
    val assetId: Long,

    /** Asset name/hostname */
    val assetName: String,

    /** Asset type (SERVER, WORKSTATION, etc.) */
    val assetType: String,

    /** Total count of overdue vulnerabilities */
    val totalOverdueCount: Int,

    /** Count of CRITICAL severity overdue vulnerabilities */
    val criticalCount: Int,

    /** Count of HIGH severity overdue vulnerabilities */
    val highCount: Int,

    /** Count of MEDIUM severity overdue vulnerabilities */
    val mediumCount: Int,

    /** Count of LOW severity overdue vulnerabilities */
    val lowCount: Int,

    /** Days since oldest vulnerability was discovered */
    val oldestVulnDays: Int,

    /** CVE ID of the oldest vulnerability */
    val oldestVulnId: String?,

    /** Timestamp when this record was calculated */
    val lastCalculatedAt: LocalDateTime
) {
    companion object {
        /**
         * Convert materialized view entity to DTO
         */
        fun from(view: com.secman.domain.OutdatedAssetMaterializedView): OutdatedAssetDto {
            return OutdatedAssetDto(
                id = view.id!!,
                assetId = view.assetId,
                assetName = view.assetName,
                assetType = view.assetType,
                totalOverdueCount = view.totalOverdueCount,
                criticalCount = view.criticalCount ?: 0,
                highCount = view.highCount ?: 0,
                mediumCount = view.mediumCount ?: 0,
                lowCount = view.lowCount ?: 0,
                oldestVulnDays = view.oldestVulnDays,
                oldestVulnId = view.oldestVulnId,
                lastCalculatedAt = view.lastCalculatedAt
            )
        }
    }
}
