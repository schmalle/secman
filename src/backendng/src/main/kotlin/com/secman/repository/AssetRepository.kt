package com.secman.repository

import com.secman.domain.Asset
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import java.util.Optional

@Repository
interface AssetRepository : JpaRepository<Asset, Long> {

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
        JOIN a.workgroups w 
        WHERE w.id IN :workgroupIds
        ORDER BY a.name ASC
    """)
    fun findByWorkgroupIdIn(workgroupIds: List<Long>): List<Asset>
}
