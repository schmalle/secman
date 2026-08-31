package com.secman.repository

import com.secman.domain.Scan
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import java.time.LocalDateTime

/**
 * Repository for Scan entity
 *
 * Provides CRUD operations and custom queries for scan management.
 */
@Repository
interface ScanRepository : JpaRepository<Scan, Long> {

    /**
     * Find all scans uploaded by a specific user
     * Used for: User's scan history
     * Returns: Paginated list ordered by scanDate DESC
     */
    fun findByUploadedByOrderByScanDateDesc(uploadedBy: String, pageable: Pageable): Page<Scan>

    /**
     * Scans uploaded by any of the given usernames, newest first.
     * Batch variant used by AssetFilterService to replace a full-table
     * findAll() followed by in-memory uploader filtering.
     */
    fun findByUploadedByInOrderByScanDateDesc(uploadedBy: Collection<String>): List<Scan>

    /**
     * Find all scans with pagination
     * Used for: Admin scan list view
     * Returns: Paginated list ordered by scanDate DESC
     */
    fun findAllOrderByScanDateDesc(pageable: Pageable): Page<Scan>

    // MCP Tool Support - Feature 006: Scan history queries with date filtering

    /**
     * Find scans within a date range
     * Used for: MCP get_scans tool with date filtering
     */
    fun findByScanDateBetween(start: LocalDateTime, end: LocalDateTime, pageable: Pageable): Page<Scan>

    /**
     * Find scans by scan type with pagination (alias for compatibility)
     * Used for: MCP get_scans tool
     */
    fun findByScanType(scanType: String, pageable: Pageable): Page<Scan>

    // Workgroup-Based Access Control - Feature 008
    // Note: Scan filtering requires service-level logic since uploadedBy is a username String,
    // not a User FK. Methods moved to ScanFilteringService.

    // Access-controlled variants (OWASP A01)
    //
    // A scan is visible to a caller when it discovered at least one asset that caller can
    // access. DISTINCT is required, not cosmetic: a scan normally produces many ScanResult
    // rows and the join would otherwise repeat the scan once per accessible host, which
    // corrupts both the page contents and the total. Filtering in SQL rather than in
    // Kotlin keeps paging honest and cost proportional to the page, not to the table.

    @Query(
        value = """
            SELECT DISTINCT s FROM Scan s
            JOIN s.results r
            WHERE r.asset.id IN :accessibleAssetIds
            ORDER BY s.scanDate DESC
        """,
        countQuery = """
            SELECT COUNT(DISTINCT s.id) FROM Scan s
            JOIN s.results r
            WHERE r.asset.id IN :accessibleAssetIds
        """
    )
    fun findAccessibleScans(accessibleAssetIds: Collection<Long>, pageable: Pageable): Page<Scan>

    @Query(
        value = """
            SELECT DISTINCT s FROM Scan s
            JOIN s.results r
            WHERE r.asset.id IN :accessibleAssetIds
            AND s.scanType = :scanType
            ORDER BY s.scanDate DESC
        """,
        countQuery = """
            SELECT COUNT(DISTINCT s.id) FROM Scan s
            JOIN s.results r
            WHERE r.asset.id IN :accessibleAssetIds
            AND s.scanType = :scanType
        """
    )
    fun findAccessibleScansByScanType(
        accessibleAssetIds: Collection<Long>,
        scanType: String,
        pageable: Pageable
    ): Page<Scan>

    @Query(
        value = """
            SELECT DISTINCT s FROM Scan s
            JOIN s.results r
            WHERE r.asset.id IN :accessibleAssetIds
            AND s.uploadedBy = :uploadedBy
            ORDER BY s.scanDate DESC
        """,
        countQuery = """
            SELECT COUNT(DISTINCT s.id) FROM Scan s
            JOIN s.results r
            WHERE r.asset.id IN :accessibleAssetIds
            AND s.uploadedBy = :uploadedBy
        """
    )
    fun findAccessibleScansByUploadedBy(
        accessibleAssetIds: Collection<Long>,
        uploadedBy: String,
        pageable: Pageable
    ): Page<Scan>

    @Query(
        value = """
            SELECT DISTINCT s FROM Scan s
            JOIN s.results r
            WHERE r.asset.id IN :accessibleAssetIds
            AND s.scanDate BETWEEN :start AND :end
            ORDER BY s.scanDate DESC
        """,
        countQuery = """
            SELECT COUNT(DISTINCT s.id) FROM Scan s
            JOIN s.results r
            WHERE r.asset.id IN :accessibleAssetIds
            AND s.scanDate BETWEEN :start AND :end
        """
    )
    fun findAccessibleScansByScanDateBetween(
        accessibleAssetIds: Collection<Long>,
        start: LocalDateTime,
        end: LocalDateTime,
        pageable: Pageable
    ): Page<Scan>
}
