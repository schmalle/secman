package com.secman.service

import com.secman.domain.Asset
import com.secman.repository.AssetRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Service for merging asset data during vulnerability import
 *
 * Handles intelligent asset merging:
 * - Find existing asset by hostname
 * - If exists: Merge (append groups, update IP, preserve owner/type/description)
 * - If not exists: Create with default values
 *
 * Related to: Feature 003-i-want-to (Vulnerability Management System)
 */
@Singleton
class AssetMergeService(
    private val assetRepository: AssetRepository
) {
    private val log = LoggerFactory.getLogger(AssetMergeService::class.java)

    companion object {
        const val DEFAULT_OWNER = "Security Team"
        const val DEFAULT_TYPE = "Server"
        const val DEFAULT_DESCRIPTION = "Auto-created from vulnerability scan"
    }

    /**
     * Find or create an asset, with intelligent merging on conflict
     *
     * @param hostname Asset name/hostname from Excel (maps to Asset.name)
     * @param ip IP address from Excel (optional)
     * @param groups Comma-separated groups from Excel (optional)
     * @param cloudAccountId Cloud account ID from Excel (optional)
     * @param cloudInstanceId Cloud instance ID from Excel (optional)
     * @param osVersion OS version from Excel (optional)
     * @param adDomain Active Directory domain from Excel (optional)
     * @return The found or created asset
     */
    fun findOrCreateAsset(
        hostname: String,
        ip: String? = null,
        groups: String? = null,
        cloudAccountId: String? = null,
        cloudInstanceId: String? = null,
        osVersion: String? = null,
        adDomain: String? = null
    ): Asset {
        val existingAsset = assetRepository.findByName(hostname)

        return if (existingAsset.isPresent) {
            log.debug("Found existing asset for hostname: {}", hostname)
            mergeAssetData(existingAsset.get(), ip, groups, cloudAccountId, cloudInstanceId, osVersion, adDomain)
        } else {
            log.debug("Creating new asset for hostname: {}", hostname)
            createAsset(hostname, ip, groups, cloudAccountId, cloudInstanceId, osVersion, adDomain)
        }
    }

    /**
     * Merge new vulnerability data into existing asset
     *
     * Merge strategy:
     * - Groups: Append new groups to existing, deduplicate
     * - IP: Update if changed
     * - CloudAccountId, CloudInstanceId, osVersion, adDomain: Update if null or if new value provided
     * - Owner, Type, Description: Preserve (never overwrite)
     *
     * @param asset Existing asset
     * @param newIp New IP address (optional)
     * @param newGroups New groups (optional)
     * @param newCloudAccountId New cloud account ID (optional)
     * @param newCloudInstanceId New cloud instance ID (optional)
     * @param newOsVersion New OS version (optional)
     * @param newAdDomain New AD domain (optional)
     * @return Updated asset
     */
    private fun mergeAssetData(
        asset: Asset,
        newIp: String?,
        newGroups: String?,
        newCloudAccountId: String?,
        newCloudInstanceId: String?,
        newOsVersion: String?,
        newAdDomain: String?
    ): Asset {
        var updated = false

        // Merge IP: Update if different
        if (newIp != null && newIp != asset.ip) {
            log.debug("Updating IP for asset {}: {} -> {}", asset.name, asset.ip, newIp)
            asset.ip = newIp
            updated = true
        }

        // Merge groups: Append and deduplicate
        if (newGroups != null) {
            val mergedGroups = mergeGroups(asset.groups, newGroups)
            if (mergedGroups != asset.groups) {
                log.debug("Updating groups for asset {}: {} -> {}", asset.name, asset.groups, mergedGroups)
                asset.groups = mergedGroups
                updated = true
            }
        }

        // Update cloud/OS/AD fields if they're currently null or if new value is different
        if (newCloudAccountId != null && newCloudAccountId != asset.cloudAccountId) {
            asset.cloudAccountId = newCloudAccountId
            updated = true
        }

        if (newCloudInstanceId != null && newCloudInstanceId != asset.cloudInstanceId) {
            asset.cloudInstanceId = newCloudInstanceId
            updated = true
        }

        if (newOsVersion != null && newOsVersion != asset.osVersion) {
            asset.osVersion = newOsVersion
            updated = true
        }

        if (newAdDomain != null && newAdDomain != asset.adDomain) {
            asset.adDomain = newAdDomain
            updated = true
        }

        // Save if any changes were made
        if (updated) {
            asset.updatedAt = LocalDateTime.now()
            return assetRepository.update(asset)
        }

        return asset
    }

    /**
     * Merge groups from existing and new sources
     *
     * - Split both on comma
     * - Trim whitespace
     * - Combine and deduplicate
     * - Return as comma-separated string
     *
     * @param existingGroups Existing groups (comma-separated string)
     * @param newGroups New groups (comma-separated string)
     * @return Merged, deduplicated, comma-separated groups
     */
    fun mergeGroups(existingGroups: String?, newGroups: String?): String? {
        val existing = existingGroups?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val new = newGroups?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        val merged = (existing + new).distinct().sorted()

        return if (merged.isEmpty()) null else merged.joinToString(", ")
    }

    /**
     * Create a new asset with default values
     *
     * Defaults:
     * - owner: "Security Team"
     * - type: "Server"
     * - description: "Auto-created from vulnerability scan"
     *
     * @param hostname Asset name/hostname
     * @param ip IP address (optional)
     * @param groups Groups (optional)
     * @param cloudAccountId Cloud account ID (optional)
     * @param cloudInstanceId Cloud instance ID (optional)
     * @param osVersion OS version (optional)
     * @param adDomain AD domain (optional)
     * @return Created asset
     */
    private fun createAsset(
        hostname: String,
        ip: String?,
        groups: String?,
        cloudAccountId: String?,
        cloudInstanceId: String?,
        osVersion: String?,
        adDomain: String?
    ): Asset {
        val asset = Asset(
            name = hostname,
            owner = DEFAULT_OWNER,
            type = DEFAULT_TYPE,
            description = DEFAULT_DESCRIPTION,
            ip = ip,
            groups = groups,
            cloudAccountId = cloudAccountId,
            cloudInstanceId = cloudInstanceId,
            osVersion = osVersion,
            adDomain = adDomain
        )

        log.info("Created new asset: {} with defaults (owner={}, type={})", hostname, DEFAULT_OWNER, DEFAULT_TYPE)
        return assetRepository.save(asset)
    }
}
