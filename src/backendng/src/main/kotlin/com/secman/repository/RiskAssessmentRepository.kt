package com.secman.repository

import com.secman.domain.RiskAssessment
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

@Repository
interface RiskAssessmentRepository : JpaRepository<RiskAssessment, Long> {
    
    // New unified basis-based queries
    fun findByAssessmentBasisTypeAndAssessmentBasisId(basisType: com.secman.domain.AssessmentBasisType, basisId: Long): List<RiskAssessment>

    fun existsByAssessmentBasisTypeAndAssessmentBasisId(
        basisType: com.secman.domain.AssessmentBasisType,
        basisId: Long
    ): Boolean

    // Convenience methods for common queries
    fun findByDemandId(demandId: Long): List<RiskAssessment> {
        return findByAssessmentBasisTypeAndAssessmentBasisId(com.secman.domain.AssessmentBasisType.DEMAND, demandId)
    }
    
    fun findByAssetId(assetId: Long): List<RiskAssessment> {
        return findByAssessmentBasisTypeAndAssessmentBasisId(com.secman.domain.AssessmentBasisType.ASSET, assetId)
    }
    
    @Query("SELECT ra FROM RiskAssessment ra WHERE ra.assessmentBasisType = :basisType AND ra.assessmentBasisId = :basisId AND ra.status = :status")
    fun findByBasisAndStatus(basisType: com.secman.domain.AssessmentBasisType, basisId: Long, status: String): List<RiskAssessment>

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

    fun countByStatus(status: String): Long

    @Query(
        """
        UPDATE RiskAssessment ra SET ra.outstandingReminderSentAt = :claimedAt
        WHERE ra.id = :id
          AND (ra.outstandingReminderSentAt IS NULL OR ra.outstandingReminderSentAt < :cutoff)
        """
    )
    fun claimOutstandingReminder(id: Long, claimedAt: LocalDateTime, cutoff: LocalDateTime): Int

    @Query(
        """
        UPDATE RiskAssessment ra SET ra.outstandingReminderSentAt = NULL
        WHERE ra.id = :id AND ra.outstandingReminderSentAt = :claimedAt
        """
    )
    fun releaseOutstandingReminderClaim(id: Long, claimedAt: LocalDateTime): Int

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

    @Query(
        value = """
            SELECT DISTINCT ra FROM RiskAssessment ra
            LEFT JOIN ra.useCases uc
            WHERE (:status IS NULL OR ra.status = :status)
              AND (:useCaseName IS NULL OR LOWER(uc.name) = LOWER(:useCaseName))
              AND (
                    :privileged = true
                    OR ra.assessor.id = :viewerId
                    OR ra.requestor.id = :viewerId
                    OR ra.respondent.id = :viewerId
              )
            ORDER BY ra.createdAt DESC
        """,
        countQuery = """
            SELECT COUNT(DISTINCT ra.id) FROM RiskAssessment ra
            LEFT JOIN ra.useCases uc
            WHERE (:status IS NULL OR ra.status = :status)
              AND (:useCaseName IS NULL OR LOWER(uc.name) = LOWER(:useCaseName))
              AND (
                    :privileged = true
                    OR ra.assessor.id = :viewerId
                    OR ra.requestor.id = :viewerId
                    OR ra.respondent.id = :viewerId
              )
        """
    )
    fun findForMcp(
        status: String?,
        useCaseName: String?,
        viewerId: Long,
        privileged: Boolean,
        pageable: Pageable
    ): Page<RiskAssessment>

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
