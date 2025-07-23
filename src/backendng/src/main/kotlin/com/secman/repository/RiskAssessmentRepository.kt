package com.secman.repository

import com.secman.domain.RiskAssessment
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDate

@Repository
interface RiskAssessmentRepository : JpaRepository<RiskAssessment, Long> {
    
    fun findByAssetId(assetId: Long): List<RiskAssessment>
    
    fun findByAssessorId(assessorId: Long): List<RiskAssessment>
    
    fun findByRequestorId(requestorId: Long): List<RiskAssessment>
    
    fun findByRespondentId(respondentId: Long): List<RiskAssessment>
    
    fun findByStatus(status: String): List<RiskAssessment>
    
    @Query("SELECT ra FROM RiskAssessment ra WHERE ra.startDate >= :startDate AND ra.endDate <= :endDate")
    fun findByDateRange(startDate: LocalDate, endDate: LocalDate): List<RiskAssessment>
    
    @Query("SELECT ra FROM RiskAssessment ra WHERE ra.asset.id = :assetId AND ra.status = :status")
    fun findByAssetIdAndStatus(assetId: Long, status: String): List<RiskAssessment>
    
    @Query("SELECT ra FROM RiskAssessment ra JOIN ra.useCases u WHERE u.id = :usecaseId")
    fun findByUsecaseId(usecaseId: Long): List<RiskAssessment>
    
    fun findByIsReleaseLocked(isLocked: Boolean): List<RiskAssessment>
}