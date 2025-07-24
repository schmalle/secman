package com.secman.repository

import com.secman.domain.Response
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface ResponseRepository : JpaRepository<Response, Long> {
    
    fun findByRiskAssessmentId(riskAssessmentId: Long): List<Response>
    
    fun findByRequirementId(requirementId: Long): List<Response>
    
    fun findByRespondentEmail(email: String): List<Response>
    
    @Query("SELECT r FROM Response r WHERE r.riskAssessment.id = :assessmentId AND r.respondentEmail = :email")
    fun findByRiskAssessmentIdAndEmail(assessmentId: Long, email: String): List<Response>
    
    @Query("SELECT r FROM Response r WHERE r.riskAssessment.id = :assessmentId AND r.requirement.id = :requirementId")
    fun findByRiskAssessmentIdAndRequirementId(assessmentId: Long, requirementId: Long): Response?
    
    @Query("SELECT COUNT(r) FROM Response r WHERE r.riskAssessment.id = :assessmentId")
    fun countByRiskAssessmentId(assessmentId: Long): Long
    
    @Query("SELECT COUNT(DISTINCT r.requirement.id) FROM Response r WHERE r.riskAssessment.id = :assessmentId")
    fun countDistinctRequirementsByAssessmentId(assessmentId: Long): Long
    
    fun deleteByRiskAssessmentId(riskAssessmentId: Long): Long
}