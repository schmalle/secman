package com.secman.repository

import com.secman.domain.AiAnswerSuggestion
import com.secman.domain.AiAnswerSuggestionStatus
import com.secman.domain.ConfidenceBand
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.transaction.annotation.Transactional

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 */
@Repository
interface AiAnswerSuggestionRepository : JpaRepository<AiAnswerSuggestion, Long> {

    fun findByJobId(jobId: Long): List<AiAnswerSuggestion>

    fun findByRiskAssessmentIdAndStatus(
        riskAssessmentId: Long,
        status: AiAnswerSuggestionStatus
    ): List<AiAnswerSuggestion>

    fun findByRiskAssessmentIdAndRequirementIdAndStatus(
        riskAssessmentId: Long,
        requirementId: Long,
        status: AiAnswerSuggestionStatus
    ): AiAnswerSuggestion?

    @Query(
        """
        SELECT s FROM AiAnswerSuggestion s
        WHERE s.riskAssessmentId = :assessmentId
          AND s.status = com.secman.domain.AiAnswerSuggestionStatus.APPLIED
          AND s.confidenceBand = :band
        """
    )
    fun findAppliedByAssessmentAndBand(
        assessmentId: Long,
        band: ConfidenceBand
    ): List<AiAnswerSuggestion>

    /**
     * Mark every APPLIED row for (assessment, requirement) as SUPERSEDED.
     * Used in the same transaction that writes a new APPLIED row from a re-run.
     */
    @Transactional
    @Query(
        value = """
            UPDATE AiAnswerSuggestion s
               SET s.status = com.secman.domain.AiAnswerSuggestionStatus.SUPERSEDED,
                   s.supersededAt = CURRENT_TIMESTAMP
             WHERE s.riskAssessmentId = :assessmentId
               AND s.requirementId    = :requirementId
               AND s.status = com.secman.domain.AiAnswerSuggestionStatus.APPLIED
        """
    )
    fun markAppliedAsSuperseded(assessmentId: Long, requirementId: Long): Long
}
