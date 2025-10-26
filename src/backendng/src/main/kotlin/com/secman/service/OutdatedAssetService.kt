package com.secman.service

import com.secman.domain.OutdatedAssetMaterializedView
import com.secman.domain.Vulnerability
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton

/**
 * Service for accessing outdated assets with workgroup-based access control
 *
 * Responsibilities:
 * - Query materialized view with pagination, sorting, filtering
 * - Apply workgroup-based access control (ADMIN sees all, VULN sees assigned workgroups only)
 * - Support search and severity filtering
 *
 * Feature: 034-outdated-assets
 * Task: T016-T019
 * User Story: US1 - View Outdated Assets (P1)
 * Spec reference: spec.md FR-008, FR-009
 */
@Singleton
class OutdatedAssetService(
    private val outdatedAssetRepository: OutdatedAssetMaterializedViewRepository,
    private val vulnerabilityRepository: VulnerabilityRepository
) {

    /**
     * Get outdated assets with workgroup-based access control
     *
     * Access Control:
     * - ADMIN role: sees all outdated assets (no filtering)
     * - VULN role: sees only assets from assigned workgroups
     * - No VULN/ADMIN: unauthorized (handled by controller @Secured)
     *
     * @param authentication Current user authentication context
     * @param searchTerm Optional search term for asset name (case-insensitive)
     * @param minSeverity Optional minimum severity filter (CRITICAL, HIGH, MEDIUM, LOW)
     * @param pageable Pagination and sorting parameters
     * @return Page of outdated assets visible to the user
     */
    fun getOutdatedAssets(
        authentication: Authentication,
        searchTerm: String? = null,
        minSeverity: String? = null,
        pageable: Pageable
    ): Page<OutdatedAssetMaterializedView> {
        // Extract user's workgroup IDs from authentication attributes
        val workgroupIds = extractWorkgroupIds(authentication)

        // Check if user has ADMIN role - if so, they see everything
        val isAdmin = authentication.roles.contains("ADMIN")

        // Build workgroup filter parameter
        val workgroupFilter = if (isAdmin) {
            // ADMIN sees all - pass null to disable workgroup filtering
            null
        } else {
            // VULN user - filter by their assigned workgroups
            // Convert list of IDs to comma-separated string for LIKE query
            workgroupIds?.joinToString(",")
        }

        // Query repository with filters
        return outdatedAssetRepository.findOutdatedAssets(
            workgroupId = workgroupFilter,
            searchTerm = searchTerm,
            minSeverity = minSeverity,
            pageable = pageable
        )
    }

    /**
     * Extract workgroup IDs from authentication context
     *
     * Workgroup IDs are stored in authentication attributes as "workgroupIds"
     * Expected format: List<Long>
     *
     * @param authentication User authentication context
     * @return List of workgroup IDs or null if not present
     */
    private fun extractWorkgroupIds(authentication: Authentication): List<Long>? {
        val workgroupIdsAttr = authentication.attributes["workgroupIds"] ?: return null

        return when (workgroupIdsAttr) {
            is List<*> -> workgroupIdsAttr.filterIsInstance<Long>()
            is Collection<*> -> workgroupIdsAttr.filterIsInstance<Long>()
            else -> null
        }
    }

    /**
     * Get the latest refresh timestamp
     *
     * @return Latest timestamp from materialized view, or null if no data
     */
    fun getLastRefreshTimestamp(): java.time.LocalDateTime? {
        return outdatedAssetRepository.findLatestCalculatedAt()
    }

    /**
     * Count total outdated assets visible to user
     *
     * @param authentication Current user authentication context
     * @return Total count respecting workgroup access control
     */
    fun countOutdatedAssets(authentication: Authentication): Long {
        val isAdmin = authentication.roles.contains("ADMIN")

        return if (isAdmin) {
            outdatedAssetRepository.count()
        } else {
            val workgroupIds = extractWorkgroupIds(authentication)
            val workgroupFilter = workgroupIds?.joinToString(",")

            outdatedAssetRepository.countOutdatedAssets(
                workgroupId = workgroupFilter
            )
        }
    }

    /**
     * Get single outdated asset by ID with access control
     *
     * Task: T032-T034
     * User Story: US2 - View Asset Details
     *
     * @param id Outdated asset materialized view ID
     * @param authentication Current user authentication context
     * @return Outdated asset or null if not found or unauthorized
     */
    fun getOutdatedAssetById(
        id: Long,
        authentication: Authentication
    ): OutdatedAssetMaterializedView? {
        val asset = outdatedAssetRepository.findById(id).orElse(null) ?: return null

        // Check access control
        val isAdmin = authentication.roles.contains("ADMIN")
        if (isAdmin) {
            // ADMIN sees all
            return asset
        }

        // VULN user - check workgroup access
        val userWorkgroupIds = extractWorkgroupIds(authentication) ?: emptyList()

        // If asset has no workgroups, allow access (backward compatibility)
        if (asset.workgroupIds.isNullOrBlank()) {
            return asset
        }

        // Parse asset's workgroup IDs
        val workgroupIdsStr = asset.workgroupIds ?: ""
        val assetWorkgroupIds = workgroupIdsStr.split(",").mapNotNull { it.toLongOrNull() }

        // Check if user has access to any of the asset's workgroups
        val hasAccess = assetWorkgroupIds.any { it in userWorkgroupIds }

        return if (hasAccess) asset else null
    }

    /**
     * Get vulnerabilities for an outdated asset
     *
     * Task: T037-T038
     * User Story: US2 - View Asset Details
     *
     * @param assetId The actual asset ID (not materialized view ID)
     * @param pageable Pagination parameters
     * @return Page of vulnerabilities for the asset
     */
    fun getVulnerabilitiesForAsset(
        assetId: Long,
        pageable: Pageable
    ): Page<Vulnerability> {
        return vulnerabilityRepository.findByAssetId(assetId, pageable)
    }
}
