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
 */
@Repository
interface ScanPortRepository : JpaRepository<ScanPort, Long> {
    @io.micronaut.data.annotation.Query(value = "SELECT p FROM ScanPort p WHERE p.scanResult.asset.id IN (SELECT a.id FROM Asset a WHERE " + AssetCurrentnessSql.JPQL + ")",
        countQuery = "SELECT COUNT(p) FROM ScanPort p WHERE p.scanResult.asset.id IN (SELECT a.id FROM Asset a WHERE " + AssetCurrentnessSql.JPQL + ")")
    override fun findAll(pageable: Pageable): Page<ScanPort>


    // MCP Tool Support - Feature 006: Product discovery across infrastructure

    /**
     * Find ports by service name (partial match, case-insensitive) with pagination
     * Used for: MCP search_products tool - finding specific services across all assets
     */
    @io.micronaut.data.annotation.Query(value = "SELECT p FROM ScanPort p WHERE p.scanResult.asset.id IN (SELECT a.id FROM Asset a WHERE " + AssetCurrentnessSql.JPQL + ") AND LOWER(p.service) LIKE LOWER(CONCAT('%', :service, '%'))", countQuery = "SELECT COUNT(p) FROM ScanPort p WHERE p.scanResult.asset.id IN (SELECT a.id FROM Asset a WHERE " + AssetCurrentnessSql.JPQL + ") AND LOWER(p.service) LIKE LOWER(CONCAT('%', :service, '%'))")
    fun findByServiceContainingIgnoreCase(service: String, pageable: Pageable): Page<ScanPort>

    /**
     * Find ports by state with non-null service name (product discovery)
     * Used for: MCP search_products tool - finding all products in a specific state (e.g., "open")
     */
    @io.micronaut.data.annotation.Query(value = "SELECT p FROM ScanPort p WHERE p.scanResult.asset.id IN (SELECT a.id FROM Asset a WHERE " + AssetCurrentnessSql.JPQL + ") AND p.state = :state AND p.service IS NOT NULL", countQuery = "SELECT COUNT(p) FROM ScanPort p WHERE p.scanResult.asset.id IN (SELECT a.id FROM Asset a WHERE " + AssetCurrentnessSql.JPQL + ") AND p.state = :state AND p.service IS NOT NULL")
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
    @io.micronaut.data.annotation.Query(value = "SELECT p FROM ScanPort p WHERE p.scanResult.asset.id IN (SELECT a.id FROM Asset a WHERE " + AssetCurrentnessSql.JPQL + ") AND p.scanResult.asset.id IN (:assetIds)", countQuery = "SELECT COUNT(p) FROM ScanPort p WHERE p.scanResult.asset.id IN (SELECT a.id FROM Asset a WHERE " + AssetCurrentnessSql.JPQL + ") AND p.scanResult.asset.id IN (:assetIds)")
    fun findByScanResultAssetIdIn(assetIds: Collection<Long>, pageable: Pageable): Page<ScanPort>

    /**
     * Find ports on the caller's accessible assets, filtered by service name.
     * Used for: MCP get_asset_scan_results / search_products with a service filter.
     */
    @io.micronaut.data.annotation.Query(value = "SELECT p FROM ScanPort p WHERE p.scanResult.asset.id IN (SELECT a.id FROM Asset a WHERE " + AssetCurrentnessSql.JPQL + ") AND p.scanResult.asset.id IN (:assetIds) AND LOWER(p.service) LIKE LOWER(CONCAT('%', :service, '%'))", countQuery = "SELECT COUNT(p) FROM ScanPort p WHERE p.scanResult.asset.id IN (SELECT a.id FROM Asset a WHERE " + AssetCurrentnessSql.JPQL + ") AND p.scanResult.asset.id IN (:assetIds) AND LOWER(p.service) LIKE LOWER(CONCAT('%', :service, '%'))")
    fun findByScanResultAssetIdInAndServiceContainingIgnoreCase(
        assetIds: Collection<Long>,
        service: String,
        pageable: Pageable
    ): Page<ScanPort>

    /**
     * Find ports on the caller's accessible assets, filtered by state, service present.
     * Used for: MCP search_products with a state filter only.
     */
    @io.micronaut.data.annotation.Query(value = "SELECT p FROM ScanPort p WHERE p.scanResult.asset.id IN (SELECT a.id FROM Asset a WHERE " + AssetCurrentnessSql.JPQL + ") AND p.scanResult.asset.id IN (:assetIds) AND p.state = :state AND p.service IS NOT NULL", countQuery = "SELECT COUNT(p) FROM ScanPort p WHERE p.scanResult.asset.id IN (SELECT a.id FROM Asset a WHERE " + AssetCurrentnessSql.JPQL + ") AND p.scanResult.asset.id IN (:assetIds) AND p.state = :state AND p.service IS NOT NULL")
    fun findByScanResultAssetIdInAndStateAndServiceNotNull(
        assetIds: Collection<Long>,
        state: String,
        pageable: Pageable
    ): Page<ScanPort>
}
