package com.secman.repository

import com.secman.domain.Response
import com.secman.domain.ResponseSource
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.transaction.annotation.Transactional

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

    // ----- Feature 088 — AI-Assisted Risk Assessment Answers ---------------

    /**
     * True iff a Response exists for this (assessment, requirement) carrying
     * `source = AI_EDITED`. Used by the re-run guard so we never silently
     * overwrite a human's edits.
     */
    @Query(
        """
        SELECT CASE WHEN COUNT(r) > 0 THEN TRUE ELSE FALSE END
          FROM Response r
         WHERE r.riskAssessment.id = :assessmentId
           AND r.requirement.id   = :requirementId
           AND r.source           = :source
        """
    )
    fun existsByAssessmentRequirementAndSource(
        assessmentId: Long,
        requirementId: Long,
        source: ResponseSource
    ): Boolean

    /**
     * Delete every Response on this assessment that is currently AI_GENERATED
     * AND linked to an AI suggestion whose confidence band is LOW. AI_EDITED
     * and MANUAL rows are not touched. Returns the count deleted.
     */
    @Transactional
    @Query(
        value = """
            DELETE FROM Response r
             WHERE r.riskAssessment.id = :assessmentId
               AND r.source = com.secman.domain.ResponseSource.AI_GENERATED
               AND r.aiSuggestionId IS NOT NULL
               AND EXISTS (
                   SELECT 1 FROM AiAnswerSuggestion s
                    WHERE s.id = r.aiSuggestionId
                      AND s.confidenceBand = com.secman.domain.ConfidenceBand.LOW
               )
        """
    )
    fun deleteLowConfidenceAiResponses(assessmentId: Long): Long
}