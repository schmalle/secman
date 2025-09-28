package com.secman.repository

import com.secman.domain.RequirementFile
import com.secman.domain.RiskAssessmentRequirementFile
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface RequirementFileRepository : JpaRepository<RequirementFile, Long> {
    
    fun findByFilename(filename: String): Optional<RequirementFile>
    
    fun findByUploadedByUsername(username: String): List<RequirementFile>
    
    @Query("SELECT rf FROM RequirementFile rf WHERE rf.uploadedBy.id = :userId")
    fun findByUploadedById(userId: Long): List<RequirementFile>
    
    fun existsByFilename(filename: String): Boolean
    
    @Query("SELECT COUNT(rf) FROM RequirementFile rf WHERE rf.uploadedBy.id = :userId")
    fun countByUploadedById(userId: Long): Long
    
    @Query("SELECT SUM(rf.fileSize) FROM RequirementFile rf WHERE rf.uploadedBy.id = :userId")
    fun getTotalFileSizeByUserId(userId: Long): Long?
}