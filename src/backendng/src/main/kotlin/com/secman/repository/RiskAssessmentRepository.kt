package com.secman.repository

import com.secman.domain.RiskAssessment
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDate

@Repository
interface RiskAssessmentRepository : JpaRepository<RiskAssessment, Long> {
    
    // New unified basis-based queries
    fun findByAssessmentBasisTypeAndAssessmentBasisId(basisType: com.secman.domain.AssessmentBasisType, basisId: Long): List<RiskAssessment>
    
    @Query("SELECT ra FROM RiskAssessment ra WHERE ra.assessmentBasisType = :basisType")
    fun findByAssessmentBasisType(basisType: com.secman.domain.AssessmentBasisType): List<RiskAssessment>
    
    @Query("SELECT ra FROM RiskAssessment ra WHERE ra.assessmentBasisType = :basisType AND ra.status = :status")
    fun findByAssessmentBasisTypeAndStatus(basisType: com.secman.domain.AssessmentBasisType, status: String): List<RiskAssessment>
    
    // Convenience methods for common queries
    fun findByDemandId(demandId: Long): List<RiskAssessment> {
        return findByAssessmentBasisTypeAndAssessmentBasisId(com.secman.domain.AssessmentBasisType.DEMAND, demandId)
    }
    
    fun findByAssetId(assetId: Long): List<RiskAssessment> {
        return findByAssessmentBasisTypeAndAssessmentBasisId(com.secman.domain.AssessmentBasisType.ASSET, assetId)
    }
    
    @Query("SELECT ra FROM RiskAssessment ra WHERE ra.assessmentBasisType = :basisType AND ra.assessmentBasisId = :basisId AND ra.status = :status")
    fun findByBasisAndStatus(basisType: com.secman.domain.AssessmentBasisType, basisId: Long, status: String): List<RiskAssessment>
    
    fun findByDemandIdAndStatus(demandId: Long, status: String): List<RiskAssessment> {
        return findByBasisAndStatus(com.secman.domain.AssessmentBasisType.DEMAND, demandId, status)
    }
    
    fun findByAssetIdAndStatus(assetId: Long, status: String): List<RiskAssessment> {
        return findByBasisAndStatus(com.secman.domain.AssessmentBasisType.ASSET, assetId, status)
    }
    
    // Legacy queries for backward compatibility with demand-based relationships
    @Query("SELECT ra FROM RiskAssessment ra LEFT JOIN ra.demand d WHERE d.demandType = :demandType AND ra.assessmentBasisType = 'DEMAND'")
    fun findByDemandType(demandType: com.secman.domain.DemandType): List<RiskAssessment>
    
    @Query("SELECT ra FROM RiskAssessment ra LEFT JOIN ra.demand d WHERE d.existingAsset.id = :assetId AND ra.assessmentBasisType = 'DEMAND'")
    fun findByExistingAssetId(assetId: Long): List<RiskAssessment>
    
    fun findByAssessorId(assessorId: Long): List<RiskAssessment>
    
    fun findByRequestorId(requestorId: Long): List<RiskAssessment>
    
    fun findByRespondentId(respondentId: Long): List<RiskAssessment>
    
    fun findByStatus(status: String): List<RiskAssessment>
    
    @Query("SELECT ra FROM RiskAssessment ra WHERE ra.startDate >= :startDate AND ra.endDate <= :endDate")
    fun findByDateRange(startDate: LocalDate, endDate: LocalDate): List<RiskAssessment>
    
    // Query to find assessments that involve a specific asset (either directly or through demands)
    @Query("""
        SELECT ra FROM RiskAssessment ra 
        LEFT JOIN ra.asset a 
        LEFT JOIN ra.demand d 
        LEFT JOIN d.existingAsset ea
        WHERE (ra.assessmentBasisType = 'ASSET' AND ra.assessmentBasisId = :assetId)
           OR (ra.assessmentBasisType = 'DEMAND' AND ea.id = :assetId)
    """)
    fun findAllByInvolvedAssetId(assetId: Long): List<RiskAssessment>
    
    @Query("SELECT ra FROM RiskAssessment ra JOIN ra.useCases u WHERE u.id = :usecaseId")
    fun findByUsecaseId(usecaseId: Long): List<RiskAssessment>
    
    fun findByIsReleaseLocked(isLocked: Boolean): List<RiskAssessment>

    /**
     * Nullify the respondent reference when a user is deleted.
     * Preserves the assessment record without blocking user deletion via the
     * risk_assessment.respondent_id → users.id FK.
     *
     * NOTE: assessor_id and requestor_id are NOT NULL on this table, so a user
     * referenced as either will still block deletion. That's a schema-level
     * follow-up (make those columns nullable) outside the scope of this fix.
     */
    @Query("UPDATE RiskAssessment ra SET ra.respondent = NULL WHERE ra.respondent.id = :userId")
    fun nullifyRespondentForUser(userId: Long): Int
}