package com.secman.repository

import com.secman.domain.Demand
import com.secman.domain.DemandStatus
import com.secman.domain.DemandType
import com.secman.domain.Priority
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface DemandRepository : JpaRepository<Demand, Long> {
    
    fun findByRequestorId(requestorId: Long): List<Demand>
    
    fun findByApproverId(approverId: Long): List<Demand>
    
    fun findByStatus(status: DemandStatus): List<Demand>
    
    fun findByDemandType(demandType: DemandType): List<Demand>

    fun findByExistingAssetId(assetId: Long): List<Demand>

    @Query("SELECT d FROM Demand d WHERE d.status = 'APPROVED' AND d.id NOT IN (SELECT ra.demand.id FROM RiskAssessment ra WHERE ra.demand IS NOT NULL)")
    fun findApprovedDemandsWithoutRiskAssessment(): List<Demand>
    
    @Query("""
        SELECT d FROM Demand d 
        WHERE (:status IS NULL OR d.status = :status) 
        AND (:demandType IS NULL OR d.demandType = :demandType)
        AND (:priority IS NULL OR d.priority = :priority)
        AND (:requestorId IS NULL OR d.requestor.id = :requestorId)
        ORDER BY d.requestedDate DESC
    """)
    fun findWithFilters(
        status: DemandStatus?,
        demandType: DemandType?,
        priority: Priority?,
        requestorId: Long?
    ): List<Demand>
    
    fun countByStatus(status: DemandStatus): Long
    
    fun countByDemandType(demandType: DemandType): Long

    /**
     * Nullify the approver reference when a user is deleted.
     * Preserves the demand record without blocking user deletion via the
     * demand.approver_id → users.id FK.
     *
     * NOTE: requestor_id is NOT NULL on this table; a user referenced as a
     * requestor will still block deletion. Schema-level follow-up.
     */
    @Query("UPDATE Demand d SET d.approver = NULL WHERE d.approver.id = :userId")
    fun nullifyApproverForUser(userId: Long): Int
}