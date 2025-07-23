package com.secman.repository

import com.secman.domain.AssessmentToken
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime
import java.util.*

@Repository
interface AssessmentTokenRepository : JpaRepository<AssessmentToken, Long> {
    
    fun findByToken(token: String): Optional<AssessmentToken>
    
    fun findByRiskAssessmentId(riskAssessmentId: Long): List<AssessmentToken>
    
    fun findByEmail(email: String): List<AssessmentToken>
    
    @Query("SELECT t FROM AssessmentToken t WHERE t.riskAssessment.id = :assessmentId AND t.email = :email")
    fun findByRiskAssessmentIdAndEmail(assessmentId: Long, email: String): Optional<AssessmentToken>
    
    @Query("SELECT t FROM AssessmentToken t WHERE t.expiresAt < :now")
    fun findExpiredTokens(now: LocalDateTime): List<AssessmentToken>
    
    @Query("SELECT t FROM AssessmentToken t WHERE t.isUsed = false AND t.expiresAt > :now")
    fun findValidTokens(now: LocalDateTime): List<AssessmentToken>
    
    @Query("SELECT t FROM AssessmentToken t WHERE t.riskAssessment.id = :assessmentId AND t.isUsed = false AND t.expiresAt > :now")
    fun findValidTokensByRiskAssessmentId(assessmentId: Long, now: LocalDateTime): List<AssessmentToken>
    
    fun deleteByRiskAssessmentId(riskAssessmentId: Long): Long
    
    fun deleteByExpiresAtBefore(date: LocalDateTime): Long
}