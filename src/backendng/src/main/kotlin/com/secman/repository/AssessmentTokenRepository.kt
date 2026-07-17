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

    /**
     * Atomically claim a one-time token. Returns 1 when this caller won the claim, 0 when the
     * token was already used (or does not exist). A read-check-write of isUsed is racy — two
     * concurrent submits both see isUsed=false and both complete the assessment; this guarded
     * UPDATE lets exactly one submit win.
     */
    @Query("UPDATE AssessmentToken t SET t.isUsed = true, t.usedAt = :now, t.updatedAt = :now WHERE t.token = :token AND t.isUsed = false")
    fun claimToken(token: String, now: LocalDateTime): Int
}