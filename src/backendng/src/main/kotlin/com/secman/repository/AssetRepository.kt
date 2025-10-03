package com.secman.repository

import com.secman.domain.Asset
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
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
}