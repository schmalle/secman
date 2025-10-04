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
}