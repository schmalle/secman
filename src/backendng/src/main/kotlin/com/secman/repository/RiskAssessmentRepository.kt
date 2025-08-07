package com.secman.repository

import com.secman.domain.RiskAssessment
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDate

@Repository
interface RiskAssessmentRepository : JpaRepository<RiskAssessment, Long> {
    
    // New demand-based queries
    fun findByDemandId(demandId: Long): List<RiskAssessment>
    
    @Query("SELECT ra FROM RiskAssessment ra WHERE ra.demand.id = :demandId AND ra.status = :status")
    fun findByDemandIdAndStatus(demandId: Long, status: String): List<RiskAssessment>
    
    @Query("SELECT ra FROM RiskAssessment ra WHERE ra.demand.demandType = :demandType")
    fun findByDemandType(demandType: com.secman.domain.DemandType): List<RiskAssessment>
    
    @Query("SELECT ra FROM RiskAssessment ra WHERE ra.demand.existingAsset.id = :assetId")
    fun findByExistingAssetId(assetId: Long): List<RiskAssessment>
    
    // Legacy asset-based queries (deprecated but kept for backward compatibility)
    @Deprecated("Use findByExistingAssetId or findByDemandId instead")
    fun findByAssetId(assetId: Long): List<RiskAssessment>
    
    fun findByAssessorId(assessorId: Long): List<RiskAssessment>
    
    fun findByRequestorId(requestorId: Long): List<RiskAssessment>
    
    fun findByRespondentId(respondentId: Long): List<RiskAssessment>
    
    fun findByStatus(status: String): List<RiskAssessment>
    
    @Query("SELECT ra FROM RiskAssessment ra WHERE ra.startDate >= :startDate AND ra.endDate <= :endDate")
    fun findByDateRange(startDate: LocalDate, endDate: LocalDate): List<RiskAssessment>
    
    // Updated query to work with both legacy asset field and demand-based assets
    @Query("""
        SELECT ra FROM RiskAssessment ra 
        WHERE (ra.asset.id = :assetId OR ra.demand.existingAsset.id = :assetId) 
        AND ra.status = :status
    """)
    fun findByAssetIdAndStatus(assetId: Long, status: String): List<RiskAssessment>
    
    @Query("SELECT ra FROM RiskAssessment ra JOIN ra.useCases u WHERE u.id = :usecaseId")
    fun findByUsecaseId(usecaseId: Long): List<RiskAssessment>
    
    fun findByIsReleaseLocked(isLocked: Boolean): List<RiskAssessment>
}