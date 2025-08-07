package com.secman.repository

import com.secman.domain.Demand
import com.secman.domain.DemandStatus
import com.secman.domain.DemandType
import com.secman.domain.Priority
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

@Repository
interface DemandRepository : JpaRepository<Demand, Long> {
    
    fun findByRequestorId(requestorId: Long): List<Demand>
    
    fun findByApproverId(approverId: Long): List<Demand>
    
    fun findByStatus(status: DemandStatus): List<Demand>
    
    fun findByDemandType(demandType: DemandType): List<Demand>
    
    fun findByPriority(priority: Priority): List<Demand>
    
    fun findByExistingAssetId(assetId: Long): List<Demand>
    
    @Query("SELECT d FROM Demand d WHERE d.status = :status AND d.requestor.id = :requestorId")
    fun findByStatusAndRequestorId(status: DemandStatus, requestorId: Long): List<Demand>
    
    @Query("SELECT d FROM Demand d WHERE d.status = :status AND d.demandType = :demandType")
    fun findByStatusAndDemandType(status: DemandStatus, demandType: DemandType): List<Demand>
    
    @Query("SELECT d FROM Demand d WHERE d.requestedDate >= :startDate AND d.requestedDate <= :endDate")
    fun findByRequestedDateRange(startDate: LocalDateTime, endDate: LocalDateTime): List<Demand>
    
    @Query("SELECT d FROM Demand d WHERE d.approvedDate >= :startDate AND d.approvedDate <= :endDate")
    fun findByApprovedDateRange(startDate: LocalDateTime, endDate: LocalDateTime): List<Demand>
    
    @Query("SELECT d FROM Demand d WHERE d.status = 'PENDING' ORDER BY d.priority DESC, d.requestedDate ASC")
    fun findPendingDemandsOrderedByPriorityAndDate(): List<Demand>
    
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
    
    fun countByRequestorId(requestorId: Long): Long
}