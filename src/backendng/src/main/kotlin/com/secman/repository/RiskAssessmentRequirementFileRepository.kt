package com.secman.repository

import com.secman.domain.RiskAssessmentRequirementFile
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface RiskAssessmentRequirementFileRepository : JpaRepository<RiskAssessmentRequirementFile, Long> {
    
    @Query("""
        SELECT rarf FROM RiskAssessmentRequirementFile rarf 
        WHERE rarf.riskAssessment.id = :riskAssessmentId 
        AND rarf.requirement.id = :requirementId
        ORDER BY rarf.createdAt DESC
    """)
    fun findByRiskAssessmentAndRequirement(
        riskAssessmentId: Long, 
        requirementId: Long
    ): List<RiskAssessmentRequirementFile>
    
    @Query("""
        SELECT rarf FROM RiskAssessmentRequirementFile rarf 
        WHERE rarf.riskAssessment.id = :riskAssessmentId
        ORDER BY rarf.createdAt DESC
    """)
    fun findByRiskAssessmentId(riskAssessmentId: Long): List<RiskAssessmentRequirementFile>
    
    @Query("""
        SELECT rarf FROM RiskAssessmentRequirementFile rarf 
        WHERE rarf.requirement.id = :requirementId
        ORDER BY rarf.createdAt DESC
    """)
    fun findByRequirementId(requirementId: Long): List<RiskAssessmentRequirementFile>
    
    @Query("""
        SELECT rarf FROM RiskAssessmentRequirementFile rarf 
        WHERE rarf.file.id = :fileId
    """)
    fun findByFileId(fileId: Long): Optional<RiskAssessmentRequirementFile>
    
    @Query("""
        SELECT rarf FROM RiskAssessmentRequirementFile rarf 
        WHERE rarf.uploadedBy.id = :userId
        ORDER BY rarf.createdAt DESC
    """)
    fun findByUploadedById(userId: Long): List<RiskAssessmentRequirementFile>
    
    @Query("""
        DELETE FROM RiskAssessmentRequirementFile rarf 
        WHERE rarf.file.id = :fileId
    """)
    fun deleteByFileId(fileId: Long): Int
    
    fun existsByFileId(fileId: Long): Boolean
    
    @Query("""
        SELECT COUNT(rarf) FROM RiskAssessmentRequirementFile rarf 
        WHERE rarf.riskAssessment.id = :riskAssessmentId 
        AND rarf.requirement.id = :requirementId
    """)
    fun countByRiskAssessmentAndRequirement(riskAssessmentId: Long, requirementId: Long): Long
}