package com.secman.dto

import com.secman.domain.Asset
import java.time.LocalDateTime

/**
 * DTO for asset export to Excel format
 *
 * Purpose: Flattened representation of Asset for Excel export with workgroup names as strings
 * Used by AssetExportService to convert Asset entities to Excel-friendly format
 *
 * Related Requirements:
 *
 * Validation: None (output-only DTO, data already validated in Asset entity)
 *
 * Usage:
 * - Convert Asset entities to DTOs before Excel serialization
 * - Workgroup names flattened to single comma-separated string for readability
 */
data class AssetExportDto(
    val name: String,
    val type: String,
    val ip: String?,
    val uri: String?,
    val owner: String,
    val description: String?,
    val groups: String?,
    val cloudAccountId: String?,
    val cloudInstanceId: String?,
    val osVersion: String?,
    val adDomain: String?,
    val workgroups: String,  // Comma-separated workgroup names
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
    val lastSeen: LocalDateTime?
) {
    companion object {
        /**
         * Factory method to create AssetExportDto from Asset entity
         * @param asset Asset entity to convert
         * @return AssetExportDto with flattened workgroup names
         */
        fun fromAsset(asset: Asset): AssetExportDto {
            return AssetExportDto(
                name = asset.name,
                type = asset.type,
                ip = asset.ip,
                uri = asset.uri,
                owner = asset.owner,
                description = asset.description,
                groups = asset.groups,
                cloudAccountId = asset.cloudAccountId,
                cloudInstanceId = asset.cloudInstanceId,
                osVersion = asset.osVersion,
                adDomain = asset.adDomain,
                workgroups = asset.workgroups.joinToString(", ") { it.name },
                createdAt = asset.createdAt,
                updatedAt = asset.updatedAt,
                lastSeen = asset.lastSeen
            )
        }
    }
}
