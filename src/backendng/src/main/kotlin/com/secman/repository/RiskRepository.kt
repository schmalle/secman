package com.secman.repository

import com.secman.domain.Risk
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDate

@Repository
interface RiskRepository : JpaRepository<Risk, Long> {
    
    fun findByAssetId(assetId: Long): List<Risk>
    
    fun findByOwnerId(ownerId: Long): List<Risk>
    
    fun findByStatus(status: String): List<Risk>
    
    fun findByRiskLevel(riskLevel: Int): List<Risk>
    
    @Query("SELECT r FROM Risk r WHERE r.deadline IS NOT NULL AND r.deadline <= :date AND r.status != 'CLOSED'")
    fun findOverdueRisks(date: LocalDate): List<Risk>
    
    @Query("SELECT r FROM Risk r WHERE r.deadline BETWEEN :startDate AND :endDate")
    fun findRisksByDeadlineRange(startDate: LocalDate, endDate: LocalDate): List<Risk>
    
    @Query("SELECT r FROM Risk r WHERE r.asset.id = :assetId AND r.status = :status")
    fun findByAssetIdAndStatus(assetId: Long, status: String): List<Risk>
    
    @Query("SELECT r FROM Risk r WHERE r.riskLevel >= :minLevel ORDER BY r.riskLevel DESC, r.deadline ASC")
    fun findHighPriorityRisks(minLevel: Int): List<Risk>
    
    fun findByNameContainingIgnoreCase(name: String): List<Risk>

    // Count queries for report aggregation
    @Query("SELECT COUNT(r) FROM Risk r WHERE r.status = :status")
    fun countByStatusValue(status: String): Long

    @Query("SELECT COUNT(r) FROM Risk r WHERE r.riskLevel = :riskLevel")
    fun countByRiskLevelValue(riskLevel: Int): Long

    @Query("SELECT COUNT(r) FROM Risk r WHERE r.status = 'OPEN'")
    fun countOpenRisks(): Long

    @Query("SELECT COUNT(r) FROM Risk r WHERE r.deadline IS NOT NULL AND r.deadline < :today AND r.status != 'CLOSED'")
    fun countOverdueRisks(today: LocalDate): Long

    @Query("SELECT COUNT(r) FROM Risk r WHERE r.owner IS NULL AND r.status = 'OPEN'")
    fun countUnassignedRisks(): Long

    // Queries with asset ID filtering for access control
    @Query("SELECT r FROM Risk r WHERE r.asset.id IN :assetIds")
    fun findByAssetIdIn(assetIds: Set<Long>): List<Risk>

    @Query("SELECT r FROM Risk r WHERE r.asset.id IN :assetIds AND r.status = :status")
    fun findByAssetIdInAndStatus(assetIds: Set<Long>, status: String): List<Risk>

    @Query("SELECT r FROM Risk r WHERE r.asset.id IN :assetIds AND r.riskLevel >= :minLevel ORDER BY r.riskLevel DESC, r.deadline ASC")
    fun findHighPriorityRisksByAssetIds(assetIds: Set<Long>, minLevel: Int): List<Risk>
}