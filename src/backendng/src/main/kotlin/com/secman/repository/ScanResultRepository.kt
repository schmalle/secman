package com.secman.repository

import com.secman.domain.ScanResult
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable

/**
 * Repository for ScanResult entity
 *
 * Provides CRUD operations and custom queries for scan result management.
 *
 * Related to:
 * - Feature: 002-implement-a-parsing (Nmap Scan Import)
 * - Contract: GET /api/assets/{id}/ports (port history)
 * - Decision 4: Point-in-time snapshots via multiple ScanResults per asset
 */
@Repository
interface ScanResultRepository : JpaRepository<ScanResult, Long> {

    /**
     * Find all scan results for an asset ordered by discovery time (newest first)
     * Used for: Port history timeline (GET /api/assets/{id}/ports)
     * Returns: List of scan results in reverse chronological order
     */
    fun findByAssetIdOrderByDiscoveredAtDesc(assetId: Long): List<ScanResult>

    /**
     * Find all scan results for a scan
     * Used for: Scan detail view (GET /api/scans/{id})
     * Returns: List of scan results (hosts discovered in scan)
     */
    fun findByScanId(scanId: Long): List<ScanResult>

    /**
     * Find scan result by scan ID and IP address
     * Used for: Duplicate detection within same scan (Decision 2)
     * Returns: Scan result if exists, null otherwise
     */
    fun findByScanIdAndIpAddress(scanId: Long, ipAddress: String): ScanResult?

    /**
     * Count scan results for an asset
     * Used for: Determining if asset has scan history
     * Returns: Number of times asset was scanned
     */
    fun countByAssetId(assetId: Long): Long

    /**
     * Count scan results for a scan
     * Used for: Verifying host count matches scan metadata
     * Returns: Number of hosts in scan
     */
    fun countByScanId(scanId: Long): Long

    /**
     * Find most recent scan result for an asset
     * Used for: Getting latest scan data for asset
     * Returns: Most recent ScanResult or null
     */
    fun findFirstByAssetIdOrderByDiscoveredAtDesc(assetId: Long): ScanResult?

    // MCP Tool Support - Feature 006: Scan result queries with pagination

    /**
     * Find all scan results for an asset with pagination
     * Used for: MCP tools querying asset scan history
     * Related to: Feature 006 (MCP Tools for Security Data)
     */
    fun findByAssetId(assetId: Long, pageable: Pageable): Page<ScanResult>

    /**
     * Find all scan results for an asset with pagination, ordered by discovery time (newest first)
     * Used for: MCP get_asset_profile tool
     * Related to: Feature 006 (MCP Tools for Security Data)
     */
    fun findByAssetIdOrderByDiscoveredAtDesc(assetId: Long, pageable: Pageable): Page<ScanResult>

    /**
     * Find all scan results for a scan with pagination
     * Used for: MCP tools querying scan details
     * Related to: Feature 006 (MCP Tools for Security Data)
     */
    fun findByScanId(scanId: Long, pageable: Pageable): Page<ScanResult>
}
