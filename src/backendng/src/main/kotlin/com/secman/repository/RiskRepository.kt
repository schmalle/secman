package com.secman.repository

import com.secman.domain.Risk
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface RiskRepository : JpaRepository<Risk, Long> {
    
    fun findByAssetId(assetId: Long): List<Risk>

    fun findByStatus(status: String): List<Risk>

    @Query("SELECT r FROM Risk r WHERE r.asset.id = :assetId AND r.status = :status")
    fun findByAssetIdAndStatus(assetId: Long, status: String): List<Risk>
    
    @Query("SELECT r FROM Risk r WHERE r.riskLevel >= :minLevel ORDER BY r.riskLevel DESC, r.deadline ASC")
    fun findHighPriorityRisks(minLevel: Int): List<Risk>

    @Query("SELECT r.status, COUNT(r) FROM Risk r GROUP BY r.status")
    fun countGroupedByStatus(): List<Array<Any?>>

    @Query("SELECT COALESCE(r.riskLevel, 0), COUNT(r) FROM Risk r GROUP BY COALESCE(r.riskLevel, 0)")
    fun countGroupedByRiskLevel(): List<Array<Any?>>

    @Query("SELECT r FROM Risk r WHERE r.status NOT IN ('CLOSED', 'MITIGATED')")
    fun findOpen(): List<Risk>
    
    fun findByNameContainingIgnoreCase(name: String): List<Risk>

    /**
     * Nullify the owner reference when a user is deleted.
     * Preserves the risk record without blocking user deletion via the
     * risk.owner_id → users.id FK.
     */
    @Query("UPDATE Risk r SET r.owner = NULL WHERE r.owner.id = :userId")
    fun nullifyOwnerForUser(userId: Long): Int
}