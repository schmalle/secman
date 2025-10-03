package com.secman.repository

import com.secman.domain.Scan
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable

/**
 * Repository for Scan entity
 *
 * Provides CRUD operations and custom queries for scan management.
 *
 * Related to:
 * - Feature: 002-implement-a-parsing (Nmap Scan Import)
 * - Contract: GET /api/scans with pagination and filtering
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
     * Find all scans by scan type
     * Used for: Filtering by scanner (nmap vs masscan)
     * Returns: Paginated list ordered by scanDate DESC
     */
    fun findByScanTypeOrderByScanDateDesc(scanType: String, pageable: Pageable): Page<Scan>

    /**
     * Find all scans with pagination
     * Used for: Admin scan list view
     * Returns: Paginated list ordered by scanDate DESC
     */
    fun findAllOrderByScanDateDesc(pageable: Pageable): Page<Scan>

    /**
     * Find scans by user and type
     * Used for: Filtered user scan history
     * Returns: Paginated list ordered by scanDate DESC
     */
    fun findByUploadedByAndScanTypeOrderByScanDateDesc(
        uploadedBy: String,
        scanType: String,
        pageable: Pageable
    ): Page<Scan>

    /**
     * Count scans by user
     * Used for: User statistics
     */
    fun countByUploadedBy(uploadedBy: String): Long

    /**
     * Count scans by type
     * Used for: Scanner statistics
     */
    fun countByScanType(scanType: String): Long
}
