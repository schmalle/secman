package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.Scan
import com.secman.domain.User
import com.secman.domain.Vulnerability
import com.secman.repository.AssetRepository
import com.secman.repository.ScanRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton

/**
 * Centralized service for workgroup-based access control filtering
 * Feature: 008-create-an-additional (Workgroup-Based Access Control)
 *
 * Implements filtering logic for assets, vulnerabilities, and scans based on:
 * - ADMIN role: Full access to all resources
 * - VULN role: Respects workgroup restrictions like regular users
 * - USER role: Access to resources from their workgroups + personally created/uploaded items
 *
 * Related Requirements:
 * - FR-013: Filter assets by workgroup membership + ownership
 * - FR-014: Filter vulnerabilities by asset accessibility
 * - FR-015: Filter scans by uploader workgroup membership
 * - FR-016: ADMIN has universal access
 * - FR-017-019: Workgroup-based filtering for regular users and VULN role
 */
@Singleton
open class AssetFilterService(
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val scanRepository: ScanRepository,
    private val userRepository: UserRepository
) {

    /**
     * Get assets accessible to the authenticated user
     * FR-013, FR-016, FR-017: Filter by workgroup + ownership, ADMIN has full access
     *
     * @param authentication Current user authentication
     * @return List of accessible assets
     */
    fun getAccessibleAssets(authentication: Authentication): List<Asset> {
        // ADMIN has universal access
        if (hasRole(authentication, "ADMIN")) {
            return assetRepository.findAll()
        }

        // Regular users and VULN: filter by workgroup membership + ownership
        val userId = getUserId(authentication)
        return assetRepository.findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(
            userId = userId,
            manualCreatorId = userId,
            scanUploaderId = userId
        )
    }

    /**
     * Get vulnerabilities accessible to the authenticated user
     * FR-014, FR-016, FR-018: Filter by asset accessibility, ADMIN has full access
     *
     * @param authentication Current user authentication
     * @return List of accessible vulnerabilities
     */
    fun getAccessibleVulnerabilities(authentication: Authentication): List<Vulnerability> {
        // ADMIN has universal access
        if (hasRole(authentication, "ADMIN")) {
            return vulnerabilityRepository.findAll()
        }

        // Regular users and VULN: filter by asset accessibility
        val userId = getUserId(authentication)
        return vulnerabilityRepository.findByAssetWorkgroupsUsersIdOrAssetManualCreatorIdOrAssetScanUploaderIdOrderByScanTimestampDesc(
            userId = userId,
            manualCreatorId = userId,
            scanUploaderId = userId
        )
    }

    /**
     * Get scans accessible to the authenticated user
     * FR-015, FR-016, FR-019: Filter by uploader workgroup, ADMIN has full access
     *
     * Note: Scan.uploadedBy is a username String, not a User FK.
     * We filter by finding users in the same workgroups and matching their usernames.
     *
     * @param authentication Current user authentication
     * @return List of accessible scans
     */
    fun getAccessibleScans(authentication: Authentication): List<Scan> {
        // ADMIN has universal access
        if (hasRole(authentication, "ADMIN")) {
            return scanRepository.findAll()
        }

        // Regular users and VULN: Get all scans uploaded by users in same workgroups
        val userId = getUserId(authentication)
        val currentUser = userRepository.findById(userId).orElseThrow {
            IllegalStateException("Current user not found: $userId")
        }

        // Get all workgroups the user belongs to
        val userWorkgroupIds = currentUser.workgroups.mapNotNull { it.id }

        if (userWorkgroupIds.isEmpty()) {
            // User not in any workgroups - can only see their own scans
            return scanRepository.findByUploadedByOrderByScanDateDesc(
                authentication.name,
                io.micronaut.data.model.Pageable.UNPAGED
            ).content
        }

        // Find all users in the same workgroups
        val usersInWorkgroups = userWorkgroupIds.flatMap { workgroupId ->
            userRepository.findByWorkgroupsIdOrderByUsernameAsc(workgroupId)
        }.distinctBy { it.id }

        // Get usernames of all users in same workgroups
        val accessibleUsernames = usersInWorkgroups.map { it.username }.toSet()

        // Filter scans by these usernames
        return scanRepository.findAll().filter { scan ->
            accessibleUsernames.contains(scan.uploadedBy)
        }.sortedByDescending { it.scanDate }
    }

    /**
     * Check if user has access to a specific asset
     * FR-020: Verify asset access before detail view
     *
     * @param assetId Asset ID to check
     * @param authentication Current user authentication
     * @return true if user can access this asset
     */
    fun canAccessAsset(assetId: Long, authentication: Authentication): Boolean {
        // ADMIN has universal access
        if (hasRole(authentication, "ADMIN")) {
            return true
        }

        // Check if asset is in user's accessible assets
        val accessibleAssets = getAccessibleAssets(authentication)
        return accessibleAssets.any { it.id == assetId }
    }

    /**
     * Get vulnerabilities for a specific asset (with access control)
     * FR-021: Filter asset vulnerabilities by accessibility
     *
     * @param assetId Asset ID
     * @param authentication Current user authentication
     * @return List of vulnerabilities for the asset (empty if no access)
     */
    fun getAssetVulnerabilities(assetId: Long, authentication: Authentication): List<Vulnerability> {
        // First check if user can access the asset
        if (!canAccessAsset(assetId, authentication)) {
            return emptyList()
        }

        // Return all vulnerabilities for this asset
        return vulnerabilityRepository.findByAssetId(assetId, io.micronaut.data.model.Pageable.UNPAGED).content
    }

    /**
     * Extract user ID from authentication
     * Uses "sub" claim from JWT token
     *
     * @param authentication Current user authentication
     * @return User ID as Long
     * @throws IllegalStateException if user ID cannot be extracted
     */
    private fun getUserId(authentication: Authentication): Long {
        return authentication.attributes["userId"]?.toString()?.toLongOrNull()
            ?: throw IllegalStateException("User ID not found in authentication")
    }

    /**
     * Check if authentication has a specific role
     *
     * @param authentication Current user authentication
     * @param role Role name to check (e.g., "ADMIN", "VULN", "USER")
     * @return true if user has the role
     */
    private fun hasRole(authentication: Authentication, role: String): Boolean {
        return authentication.roles.contains(role)
    }
}
