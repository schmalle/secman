package com.secman.repository

import com.secman.domain.AiSuggestionJob
import com.secman.domain.AiSuggestionJobStatus
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 */
@Repository
interface AiSuggestionJobRepository : JpaRepository<AiSuggestionJob, Long> {

    fun findByStatusIn(statuses: List<AiSuggestionJobStatus>): List<AiSuggestionJob>

    fun findByRiskAssessmentIdAndStatusIn(
        riskAssessmentId: Long,
        statuses: List<AiSuggestionJobStatus>
    ): List<AiSuggestionJob>

    fun countByStatusIn(statuses: List<AiSuggestionJobStatus>): Long

    @Query(
        """
        SELECT j FROM AiSuggestionJob j
        WHERE j.status IN :statuses
          AND (j.lastHeartbeatAt IS NULL OR j.lastHeartbeatAt < :threshold)
        """
    )
    fun findStaleByStatus(
        statuses: List<AiSuggestionJobStatus>,
        threshold: java.time.LocalDateTime
    ): List<AiSuggestionJob>
}
