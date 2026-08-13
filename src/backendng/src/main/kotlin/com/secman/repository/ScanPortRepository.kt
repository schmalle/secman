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

    // Access-controlled variants (OWASP A01)
    //
    // The unscoped methods above answer "every port in the estate" and are correct only
    // for an ADMIN caller. Every other caller must go through one of these, binding the
    // delegated user's accessible asset ids. The filter runs in SQL on purpose: reading
    // the unscoped page and dropping rows in Kotlin would still leak the total count and
    // return short pages, and cost would grow with the table rather than the page.
    //
    // ScanPort -> scanResult -> asset is the only path from a port to its owning asset.

    /**
     * Find ports on the caller's accessible assets.
     * Used for: MCP get_asset_scan_results with no service filter.
     */
    fun findByScanResultAssetIdIn(assetIds: Collection<Long>, pageable: Pageable): Page<ScanPort>

    /**
     * Find ports on the caller's accessible assets, filtered by service name.
     * Used for: MCP get_asset_scan_results / search_products with a service filter.
     */
    fun findByScanResultAssetIdInAndServiceContainingIgnoreCase(
        assetIds: Collection<Long>,
        service: String,
        pageable: Pageable
    ): Page<ScanPort>

    /**
     * Find ports on the caller's accessible assets, filtered by state, service present.
     * Used for: MCP search_products with a state filter only.
     */
    fun findByScanResultAssetIdInAndStateAndServiceNotNull(
        assetIds: Collection<Long>,
        state: String,
        pageable: Pageable
    ): Page<ScanPort>
}
