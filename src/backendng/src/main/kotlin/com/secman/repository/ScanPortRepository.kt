package com.secman.repository

import com.secman.domain.ScanPort
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable

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

    // MCP Tool Support - Feature 006: Product discovery across infrastructure

    /**
     * Find ports by service name (partial match, case-insensitive) with pagination
     * Used for: MCP search_products tool - finding specific services across all assets
     * Related to: Feature 006 (MCP Tools for Security Data)
     */
    fun findByServiceContainingIgnoreCase(service: String, pageable: Pageable): Page<ScanPort>

    /**
     * Find ports by state with non-null service name (product discovery)
     * Used for: MCP search_products tool - finding all products in a specific state (e.g., "open")
     * Related to: Feature 006 (MCP Tools for Security Data)
     */
    fun findByStateAndServiceNotNull(state: String, pageable: Pageable): Page<ScanPort>
}
