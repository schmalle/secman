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
}
