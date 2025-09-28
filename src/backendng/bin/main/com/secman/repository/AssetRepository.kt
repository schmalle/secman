package com.secman.repository

import com.secman.domain.Asset
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface AssetRepository : JpaRepository<Asset, Long> {
    
    fun findByNameContainingIgnoreCase(name: String): List<Asset>
    
    fun findByType(type: String): List<Asset>
    
    fun findByOwner(owner: String): List<Asset>
    
    fun findByIp(ip: String): List<Asset>
    
    fun findByTypeAndOwner(type: String, owner: String): List<Asset>
}