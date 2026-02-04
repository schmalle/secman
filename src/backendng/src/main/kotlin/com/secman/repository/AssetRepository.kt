package com.secman.repository

import com.secman.domain.Asset
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import java.util.Optional

@Repository
interface AssetRepository : JpaRepository<Asset, Long> {

    // Memory Optimization - Feature 073

    /**
     * Find asset by ID with workgroups eagerly loaded
     * Used for detail/update operations when LAZY loading is enabled
     *
     * Feature: 073-memory-optimization
     * Task: T006
     *
     * @param id The asset ID
     * @return Optional containing the asset with workgroups loaded
     */
    @io.micronaut.data.annotation.Query("""
        SELECT a FROM Asset a
        LEFT JOIN FETCH a.workgroups
        WHERE a.id = :id
    """)
    fun findByIdWithWorkgroups(id: Long): Optional<Asset>

    /**
     * Find all assets accessible to a user using unified access control query
     * Combines all access criteria in a single database round trip:
     * 1. Assets in user's workgroups
     * 2. Assets manually created by user
     * 3. Assets discovered via user's scan upload
     * 4. Assets with cloudAccountId matching user's AWS mappings
     * 5. Assets with adDomain matching user's domain mappings
     *
     * Feature: 073-memory-optimization
     * Task: T031
     *
     * @param userId The user's ID (for workgroup, creator, uploader checks)
     * @param userEmail The user's email (for AWS account and domain mapping lookups)
     * @return List of distinct accessible assets, ordered by name
     */
    @io.micronaut.data.annotation.Query(
        value = """
            SELECT DISTINCT a.* FROM asset a
            WHERE
                a.id IN (
                    SELECT aw.asset_id FROM asset_workgroups aw
                    JOIN user_workgroups uw ON aw.workgroup_id = uw.workgroup_id
                    WHERE uw.user_id = :userId
                )
                OR a.manual_creator_id = :userId
                OR a.scan_uploader_id = :userId
                OR a.cloud_account_id IN (
                    SELECT um.aws_account_id FROM user_mapping um
                    WHERE um.email = :userEmail AND um.aws_account_id IS NOT NULL
                )
                OR LOWER(a.ad_domain) IN (
                    SELECT LOWER(um.domain) FROM user_mapping um
                    WHERE um.email = :userEmail AND um.domain IS NOT NULL
                )
            ORDER BY a.name ASC
        """,
        nativeQuery = true
    )
    fun findAccessibleAssets(userId: Long, userEmail: String): List<Asset>

    fun findByNameContainingIgnoreCase(name: String): List<Asset>

    fun findByType(type: String): List<Asset>

    fun findByOwner(owner: String): List<Asset>

    fun findByIp(ip: String): List<Asset>

    fun findByTypeAndOwner(type: String, owner: String): List<Asset>

    /**
     * Find asset by exact name match
     * Used for hostname lookup during vulnerability import
     * Related to: Feature 003-i-want-to (Vulnerability Management System)
     *
     * @param name The asset name (hostname)
     * @return Optional containing the asset if found
     */
    fun findByName(name: String): Optional<Asset>

    /**
     * Find asset by case-insensitive name match
     * Prevents duplicate asset creation with different casing (e.g., "SERVER1" vs "server1")
     * Related to: Feature 030 (CrowdStrike Asset Auto-Creation) - FR-006
     *
     * @param name The asset name (hostname, case-insensitive)
     * @return The asset if found, null otherwise
     */
    fun findByNameIgnoreCase(name: String): Asset?

    /**
     * Find asset by cloud instance ID (case-insensitive)
     * Used for database-first vulnerability lookup by AWS EC2 Instance ID
     *
     * @param cloudInstanceId The AWS EC2 Instance ID (e.g., "i-0068f94221fe120df")
     * @return The asset if found, null otherwise
     */
    fun findByCloudInstanceIdIgnoreCase(cloudInstanceId: String): Asset?

    // MCP Tool Support - Feature 006: Asset inventory queries with pagination

    /**
     * Find assets by group membership (partial match in comma-separated groups field)
     * Related to: Feature 006 (MCP Tools for Security Data)
     */
    fun findByGroupsContaining(group: String): List<Asset>

    /**
     * Find assets by IP address (partial match, case-insensitive)
     * Related to: Feature 006 (MCP Tools for Security Data)
     */
    fun findByIpContainingIgnoreCase(ip: String): List<Asset>

    /**
     * Find all assets with pagination support
     * Related to: Feature 006 (MCP Tools for Security Data)
     */
    override fun findAll(pageable: Pageable): Page<Asset>

    /**
     * Find assets by name with pagination support (partial match, case-insensitive)
     * Related to: Feature 006 (MCP Tools for Security Data)
     */
    fun findByNameContainingIgnoreCase(name: String, pageable: Pageable): Page<Asset>

    // Workgroup-Based Access Control - Feature 008

    /**
     * Find assets accessible to a specific user based on workgroup membership
     * Returns assets that are either:
     * 1. In workgroups the user belongs to
     * 2. Created manually by the user
     * 3. Discovered via scans uploaded by the user
     *
     * Related to: Feature 008 (Workgroup-Based Access Control) - FR-013, FR-017
     *
     * @param userId The user ID to filter by
     * @return List of assets accessible to the user
     */
    fun findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderIdOrderByNameAsc(
        userId: Long,
        manualCreatorId: Long,
        scanUploaderId: Long
    ): List<Asset>

    /**
     * Find assets in specific workgroups
     * Used for admin workgroup management views
     *
     * Related to: Feature 008 (Workgroup-Based Access Control) - FR-009
     *
     * @param workgroupId The workgroup ID to filter by
     * @return List of assets in the specified workgroup
     */
    fun findByWorkgroupsIdOrderByNameAsc(workgroupId: Long): List<Asset>

    /**
     * Find assets by cloud account IDs
     * Used for Account Vulns view to filter assets by user's AWS account mappings
     *
     * Related to: Feature 018 (Account Vulns - AWS Account-Based Vulnerability Overview)
     *
     * @param cloudAccountIds List of AWS account IDs to filter by
     * @return List of assets in the specified AWS accounts
     */
    fun findByCloudAccountIdIn(cloudAccountIds: List<String>): List<Asset>

    // IP Address Mapping - Feature 020

    /**
     * Find assets with IP addresses in a numeric range
     * Used for IP-based access control filtering
     *
     * Related to: Feature 020 (IP Address Mapping)
     *
     * @param startIp Start of IP range (numeric)
     * @param endIp End of IP range (numeric)
     * @return List of assets with IPs in the specified range
     */
    fun findByIpNumericBetween(startIp: Long, endIp: Long): List<Asset>

    /**
     * Find assets with IP addresses matching any of the provided numeric ranges
     * Used for IP-based access control when user has multiple IP mappings
     *
     * Note: This is a convenience method for the common case where a user has multiple IP ranges.
     * For complex queries, use custom JPA queries in the service layer.
     *
     * Related to: Feature 020 (IP Address Mapping)
     *
     * @param ipNumeric Single IP address (numeric)
     * @return List of assets with the specified IP
     */
    fun findByIpNumeric(ipNumeric: Long): List<Asset>

    /**
     * Find assets that belong to any of the specified workgroups
     * Used for WG Vulns feature (022-wg-vulns-handling)
     * Feature 073: Uses LEFT JOIN FETCH to eagerly load workgroups with LAZY loading.
     *
     * This query joins the asset table with the asset_workgroups join table
     * and filters by workgroup IDs. Returns distinct assets to avoid duplicates
     * when an asset belongs to multiple specified workgroups.
     *
     * @param workgroupIds List of workgroup IDs to filter by
     * @return List of distinct assets in the specified workgroups (empty list if none)
     */
    @io.micronaut.data.annotation.Query("""
        SELECT DISTINCT a FROM Asset a
        LEFT JOIN FETCH a.workgroups
        WHERE a.id IN (
            SELECT DISTINCT a2.id FROM Asset a2
            JOIN a2.workgroups w
            WHERE w.id IN :workgroupIds
        )
        ORDER BY a.name ASC
    """)
    fun findByWorkgroupIdIn(workgroupIds: List<Long>): List<Asset>

    /**
     * Find distinct AD domains from all assets
     * Used for filter dropdown population in Current Vulnerabilities view
     *
     * @return List of distinct AD domain names (non-null, non-empty), ordered alphabetically
     */
    @io.micronaut.data.annotation.Query("""
        SELECT DISTINCT a.adDomain
        FROM Asset a
        WHERE a.adDomain IS NOT NULL
        AND a.adDomain != ''
        ORDER BY a.adDomain
    """)
    fun findDistinctAdDomains(): List<String>

    /**
     * Find distinct AWS cloud account IDs from all assets
     * Used for filter dropdown population in Current Vulnerabilities view
     *
     * @return List of distinct cloud account IDs (non-null, non-empty), ordered alphabetically
     */
    @io.micronaut.data.annotation.Query("""
        SELECT DISTINCT a.cloudAccountId
        FROM Asset a
        WHERE a.cloudAccountId IS NOT NULL
        AND a.cloudAccountId != ''
        ORDER BY a.cloudAccountId
    """)
    fun findDistinctCloudAccountIds(): List<String>

    // Database Optimization - Feature: Database Structure Optimization

    /**
     * Find assets by AD domain (case-insensitive match)
     * Optimized query for domain-based access control filtering
     * Uses index: idx_asset_ad_domain
     *
     * Feature: Database Structure Optimization
     *
     * @param domains List of AD domain names (lowercase)
     * @return List of assets matching any of the specified domains
     */
    @io.micronaut.data.annotation.Query("""
        SELECT a FROM Asset a
        WHERE LOWER(a.adDomain) IN :domains
        ORDER BY a.name ASC
    """)
    fun findByAdDomainInIgnoreCase(domains: List<String>): List<Asset>

    /**
     * Find assets by name or name starting with "name." (FQDN check)
     * Used to detect duplicate assets (e.g. "server1" vs "server1.domain.com")
     *
     * Feature: 053-crowdstrike-import-cleanup
     *
     * @param name The short hostname
     * @return List of potential duplicate assets
     */
    @io.micronaut.data.annotation.Query("""
        SELECT a FROM Asset a
        WHERE LOWER(a.name) = LOWER(:name) 
        OR LOWER(a.name) LIKE LOWER(CONCAT(:name, '.%'))
    """)
    fun findPotentialDuplicates(name: String): List<Asset>

    /**
     * Find all assets with non-null AD domain
     * Optimized for domain filtering operations
     * Uses index: idx_asset_ad_domain
     *
     * Feature: Database Structure Optimization
     *
     * @return List of assets with AD domain set
     */
    @io.micronaut.data.annotation.Query("""
        SELECT a FROM Asset a
        WHERE a.adDomain IS NOT NULL
        ORDER BY a.name ASC
    """)
    fun findAllWithAdDomain(): List<Asset>

    // Feature 054: Products Overview - Asset queries by product

    /**
     * Find assets running a specific product with access control and pagination
     * Returns distinct assets that have vulnerabilities with the specified product
     *
     * Feature: 054-products-overview
     * Task: T006
     *
     * @param product The product name to search for (exact match on vulnerableProductVersions)
     * @param accessibleAssetIds Set of asset IDs the user has access to
     * @param pageable Pagination parameters
     * @return Page of assets running the specified product
     */
    @io.micronaut.data.annotation.Query(
        value = """
            SELECT DISTINCT a FROM Asset a
            JOIN Vulnerability v ON v.asset.id = a.id
            WHERE v.vulnerableProductVersions = :product
            AND a.id IN :accessibleAssetIds
            ORDER BY a.name ASC
        """,
        countQuery = """
            SELECT COUNT(DISTINCT a.id) FROM Asset a
            JOIN Vulnerability v ON v.asset.id = a.id
            WHERE v.vulnerableProductVersions = :product
            AND a.id IN :accessibleAssetIds
        """
    )
    fun findAssetsByProductWithAccessControl(
        product: String,
        accessibleAssetIds: Set<Long>,
        pageable: Pageable
    ): Page<Asset>

    /**
     * Find assets running a specific product for admin users (no access control) with pagination
     * Returns distinct assets that have vulnerabilities with the specified product
     *
     * Feature: 054-products-overview
     * Task: T006
     *
     * @param product The product name to search for (exact match on vulnerableProductVersions)
     * @param pageable Pagination parameters
     * @return Page of assets running the specified product
     */
    @io.micronaut.data.annotation.Query(
        value = """
            SELECT DISTINCT a FROM Asset a
            JOIN Vulnerability v ON v.asset.id = a.id
            WHERE v.vulnerableProductVersions = :product
            ORDER BY a.name ASC
        """,
        countQuery = """
            SELECT COUNT(DISTINCT a.id) FROM Asset a
            JOIN Vulnerability v ON v.asset.id = a.id
            WHERE v.vulnerableProductVersions = :product
        """
    )
    fun findAssetsByProductForAll(
        product: String,
        pageable: Pageable
    ): Page<Asset>

    /**
     * Count assets running a specific product with access control
     * Used for pagination total count
     *
     * Feature: 054-products-overview
     * Task: T007
     *
     * @param product The product name to search for
     * @param accessibleAssetIds Set of asset IDs the user has access to
     * @return Count of assets running the specified product
     */
    @io.micronaut.data.annotation.Query("""
        SELECT COUNT(DISTINCT a.id) FROM Asset a
        JOIN Vulnerability v ON v.asset.id = a.id
        WHERE v.vulnerableProductVersions = :product
        AND a.id IN :accessibleAssetIds
    """)
    fun countAssetsByProductWithAccessControl(product: String, accessibleAssetIds: Set<Long>): Long

    /**
     * Count all assets running a specific product (admin view)
     * Used for pagination total count
     *
     * Feature: 054-products-overview
     * Task: T007
     *
     * @param product The product name to search for
     * @return Count of all assets running the specified product
     */
    @io.micronaut.data.annotation.Query("""
        SELECT COUNT(DISTINCT a.id) FROM Asset a
        JOIN Vulnerability v ON v.asset.id = a.id
        WHERE v.vulnerableProductVersions = :product
    """)
    fun countAssetsByProductForAll(product: String): Long

    /**
     * Find all assets running a specific product with access control (no pagination)
     * Used for export functionality
     *
     * Feature: 054-products-overview
     * Task: T025 (Export)
     *
     * @param product The product name to search for
     * @param accessibleAssetIds Set of asset IDs the user has access to
     * @return List of all assets running the specified product
     */
    @io.micronaut.data.annotation.Query("""
        SELECT DISTINCT a FROM Asset a
        JOIN Vulnerability v ON v.asset.id = a.id
        WHERE v.vulnerableProductVersions = :product
        AND a.id IN :accessibleAssetIds
        ORDER BY a.name ASC
    """)
    fun findAssetsByProductWithAccessControlNoLimit(
        product: String,
        accessibleAssetIds: Set<Long>
    ): List<Asset>

    /**
     * Find all assets running a specific product (admin view, no pagination)
     * Used for export functionality
     *
     * Feature: 054-products-overview
     * Task: T025 (Export)
     *
     * @param product The product name to search for
     * @return List of all assets running the specified product
     */
    @io.micronaut.data.annotation.Query("""
        SELECT DISTINCT a FROM Asset a
        JOIN Vulnerability v ON v.asset.id = a.id
        WHERE v.vulnerableProductVersions = :product
        ORDER BY a.name ASC
    """)
    fun findAssetsByProductForAllNoLimit(product: String): List<Asset>
}
