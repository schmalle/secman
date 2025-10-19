package com.secman.dto

import com.secman.domain.Asset
import com.secman.domain.Workgroup
import java.time.LocalDateTime

/**
 * DTO for asset import from Excel format
 * Feature: 029-asset-bulk-operations (User Story 3 - Import Assets from File)
 *
 * Purpose: Temporary representation of parsed Excel row before entity creation
 * Used by AssetImportService to validate and convert Excel data to Asset entities
 *
 * Related Requirements:
 * - FR-017: Accept Excel files with validation for file size, format, required fields
 * - FR-018: Validate required fields (name, type, owner)
 * - FR-019: Validate data formats (IP address, type values)
 * - FR-021: Associate imported assets with workgroups based on column data
 * - Contract: contracts/asset-import.yaml
 *
 * Validation Rules:
 * - name: Required, non-blank, trimmed
 * - type: Required, non-blank, trimmed
 * - owner: Required, non-blank, trimmed
 * - ip: Optional, validated for IPv4/IPv6 format if present
 * - workgroupNames: Optional, comma-separated string parsed to workgroup entities
 *
 * Usage:
 * - Parse Excel row to DTO with validation
 * - Convert DTO to Asset entity after workgroup name resolution
 * - Validation errors collected per-row for user feedback
 */
data class AssetImportDto(
    val name: String,
    val type: String,
    val ip: String? = null,
    val owner: String,
    val description: String? = null,
    val groups: String? = null,
    val cloudAccountId: String? = null,
    val cloudInstanceId: String? = null,
    val osVersion: String? = null,
    val adDomain: String? = null,
    val workgroupNames: String? = null,  // Comma-separated, parsed to Workgroup entities
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
    val lastSeen: LocalDateTime? = null
) {
    /**
     * Convert DTO to Asset entity
     * @param workgroups Resolved workgroup entities from workgroupNames
     * @return Asset entity ready for persistence
     */
    fun toAsset(workgroups: Set<Workgroup> = emptySet()): Asset {
        return Asset(
            name = name.trim(),
            type = type.trim(),
            ip = ip?.trim(),
            owner = owner.trim(),
            description = description?.trim(),
            groups = groups?.trim(),
            cloudAccountId = cloudAccountId?.trim(),
            cloudInstanceId = cloudInstanceId?.trim(),
            osVersion = osVersion?.trim(),
            adDomain = adDomain?.trim(),
            workgroups = workgroups.toMutableSet(),
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastSeen = lastSeen
        )
    }
}
