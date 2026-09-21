package com.secman.service

import com.secman.config.MemoryOptimizationConfig
import com.secman.domain.Asset
import com.secman.domain.Scan
import com.secman.domain.Vulnerability
import com.secman.repository.AssetRepository
import com.secman.repository.ScanRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.WorkgroupAdDomainRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import com.secman.security.hasRole
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton

/** Shared asset visibility policy for REST, MCP and derived resources. */
@Singleton
open class AssetFilterService(
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val scanRepository: ScanRepository,
    private val userRepository: UserRepository,
    private val userMappingRepository: UserMappingRepository,
    private val memoryConfig: MemoryOptimizationConfig,
    private val awsAccountSharingService: AwsAccountSharingService,
    private val workgroupAwsAccountRepository: WorkgroupAwsAccountRepository,
    private val workgroupAdDomainRepository: WorkgroupAdDomainRepository
) {

    /** Global roles see all assets; other users need an explicit current grant. */
    fun getAccessibleAssets(authentication: Authentication): List<Asset> {
        // ADMIN and SECCHAMPION have universal access
        if (authentication.hasRole("ADMIN") || authentication.hasRole("SECCHAMPION")) {
            return assetRepository.findAll()
        }

        return getAccessibleAssetsForScopedUser(authentication)
    }

    fun getScopedAccessibleAssetIds(authentication: Authentication): Set<Long> {
        if (authentication.hasRole("ADMIN")) {
            return assetRepository.findAllIds().toSet()
        }
        return getAccessibleAssetsForScopedUser(authentication).mapNotNull { it.id }.toSet()
    }

    private fun getAccessibleAssetsForScopedUser(authentication: Authentication): List<Asset> {
        val userId = getUserId(authentication)
        val userEmail = getUserEmail(authentication)

        // Feature 073: Use unified query when memory optimization is enabled
        if (memoryConfig.lazyLoadingEnabled && userEmail != null) {
            return getAccessibleAssetsUnified(userId, userEmail)
        }

        // Fallback: Original multi-query approach
        return getAccessibleAssetsMultiQuery(userId, userEmail)
    }

    /**
     * Get asset IDs accessible to the authenticated user.
     *
     * This helper avoids repeatedly materializing full Asset entities for call sites
     * that only need membership checks or `IN (:assetIds)` filtering.
     */
    fun getAccessibleAssetIds(authentication: Authentication): Set<Long> {
        if (authentication.hasRole("ADMIN") || authentication.hasRole("SECCHAMPION")) {
            return assetRepository.findAllIds().toSet()
        }
        val email = getUserEmail(authentication)
        return if (email != null) {
            assetRepository.findAccessibleAssetIds(getUserId(authentication), email).toSet()
        } else {
            getAccessibleAssetsForScopedUser(authentication).mapNotNull { it.id }.toSet()
        }
    }

    /**
     * Get accessible assets using unified single-query approach
     * Feature 073: Combines all access criteria in one database round trip.
     *
     * @param userId The user's ID
     * @param userEmail The user's email
     * @return List of accessible assets (already distinct and sorted)
     */
    private fun getAccessibleAssetsUnified(userId: Long, userEmail: String): List<Asset> {
        return assetRepository.findAccessibleAssets(userId, userEmail)
    }

    /**
     * Get accessible assets using original multi-query approach
     * Retained for stability and feature flag rollback.
     *
     * @param userId The user's ID
     * @param userEmail The user's email (nullable)
     * @return List of accessible assets (deduplicated and sorted)
     */
    private fun getAccessibleAssetsMultiQuery(userId: Long, userEmail: String?): List<Asset> {
        val directAssets = assetRepository.findAccessibleByWorkgroupMembership(userId)
        val personalAccounts = userEmail?.let(userMappingRepository::findDistinctAwsAccountIdByEmail).orEmpty()
        val personalDomains = userEmail?.let(userMappingRepository::findDistinctDomainByEmail).orEmpty()
        val accountIds = (personalAccounts +
            workgroupAwsAccountRepository.findDistinctAwsAccountIdsByUserId(userId) +
            awsAccountSharingService.getSharedAwsAccountIds(userId)).distinct()
        val domains = (personalDomains + workgroupAdDomainRepository.findDistinctAdDomainsByUserId(userId))
            .map { it.lowercase() }.distinct()
        val accountAssets = if (accountIds.isEmpty()) emptyList() else assetRepository.findByCloudAccountIdIn(accountIds)
        val domainAssets = if (domains.isEmpty()) emptyList() else assetRepository.findByAdDomainInIgnoreCase(domains)
        return (directAssets + accountAssets + domainAssets).distinctBy { it.id }.sortedBy { it.name }
    }

    // getAccessibleVulnerabilities was deleted here: it had no callers, and both of its
    // branches (findAll() / unbounded findLatestVulnerabilitiesForAssetIds) were the exact
    // full-table shape that ran a 1 GB container out of heap on 2026-07-30. A future caller
    // needs a Pageable or an aggregate — `countLatestVulnerabilitiesBySeverityForAssetIds`
    // is the counting equivalent.

    /** A scan is visible only when all its linked assets are visible. */
    fun getAccessibleScans(authentication: Authentication): List<Scan> {
        if (authentication.hasRole("ADMIN") || authentication.hasRole("SECCHAMPION")) {
            return scanRepository.findAll()
        }
        val assetIds = getAccessibleAssetIds(authentication)
        if (assetIds.isEmpty()) return emptyList()
        return scanRepository.findFullyAccessibleScans(assetIds)
    }

    /** Whole-account authority must never be inferred from access to one asset. */
    fun canAccessAwsAccount(accountId: String, authentication: Authentication): Boolean {
        if (authentication.hasRole("ADMIN") || authentication.hasRole("SECCHAMPION")) return true
        return accountId in getAccessibleAwsAccountIds(authentication)
    }

    /** Whole-account authority is independent of access to individual assets. */
    fun getAccessibleAwsAccountIds(authentication: Authentication): Set<String> {
        val userId = getUserId(authentication)
        val personal = getUserEmail(authentication)?.let {
            userMappingRepository.findDistinctAwsAccountIdByEmail(it)
        }.orEmpty()
        return personal.toSet() + workgroupAwsAccountRepository.findDistinctAwsAccountIdsByUserId(userId) +
            awsAccountSharingService.getSharedAwsAccountIds(userId)
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
        // ADMIN and SECCHAMPION have universal access
        if (authentication.hasRole("ADMIN") || authentication.hasRole("SECCHAMPION")) {
            return true
        }

        // Check if asset is in user's accessible assets
        return getAccessibleAssetIds(authentication).contains(assetId)
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
     * Extract user email from authentication
     * Used for AWS account mapping lookups
     *
     * @param authentication Current user authentication
     * @return User email as String, or null if not found
     */
    private fun getUserEmail(authentication: Authentication): String? {
        return authentication.attributes["email"]?.toString()
    }

}
