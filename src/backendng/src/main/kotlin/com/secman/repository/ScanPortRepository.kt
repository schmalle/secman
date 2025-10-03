package com.secman.repository

import com.secman.domain.ScanPort
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/**
 * Repository for ScanPort entity
 *
 * Provides CRUD operations and custom queries for port data management.
 *
 * Related to:
 * - Feature: 002-implement-a-parsing (Nmap Scan Import)
 * - Contract: GET /api/assets/{id}/ports (port history with details)
 * - FR-011: Display port history with numbers, states, services
 */
@Repository
interface ScanPortRepository : JpaRepository<ScanPort, Long> {

    /**
     * Find all ports for a scan result
     * Used for: Loading port details for a specific host scan
     * Returns: List of ports discovered for the host
     */
    fun findByScanResultId(scanResultId: Long): List<ScanPort>

    /**
     * Find open ports for a scan result
     * Used for: Filtering only open ports
     * Returns: List of ports with state='open'
     */
    fun findByScanResultIdAndState(scanResultId: Long, state: String): List<ScanPort>

    /**
     * Count ports for a scan result
     * Used for: Port count statistics in scan summary
     * Returns: Number of ports discovered for host
     */
    fun countByScanResultId(scanResultId: Long): Long

    /**
     * Find ports by scan result and protocol
     * Used for: Filtering TCP vs UDP ports
     * Returns: List of ports matching protocol
     */
    fun findByScanResultIdAndProtocol(scanResultId: Long, protocol: String): List<ScanPort>

    /**
     * Find specific port on scan result
     * Used for: Checking if specific port was found
     * Returns: ScanPort or null
     */
    fun findByScanResultIdAndPortNumberAndProtocol(
        scanResultId: Long,
        portNumber: Int,
        protocol: String
    ): ScanPort?

    /**
     * Count open ports for a scan result
     * Used for: Statistics on open ports
     * Returns: Number of ports with state='open'
     */
    fun countByScanResultIdAndState(scanResultId: Long, state: String): Long
}
