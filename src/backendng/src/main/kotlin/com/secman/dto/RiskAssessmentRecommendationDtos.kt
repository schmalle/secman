package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

/**
 * Shared deterministic risk-assessment recommendation, computed once by
 * [com.secman.service.RiskAssessmentRecommendationService] and served on both
 * the MCP `evaluate_risk_assessment` surface and REST
 * `GET /api/risk-assessments/{id}/recommendation`. Advisory only — acceptance
 * stays with an independent human reviewer.
 */

@Serdeable
enum class Recommendation {
    OK,
    NOT_OK,
    NEEDS_REVIEW
}

@Serdeable
data class RecommendationFinding(
    val requirementId: Long,
    val internalId: String?,
    val shortreq: String?,
    val answerType: String?,
    val reason: String,
    val comment: String?
)

@Serdeable
data class RiskAssessmentRecommendation(
    val assessmentId: Long,
    val answerRevision: Long,
    val recommendation: Recommendation,
    val verdict: String,
    val summary: String,
    val answerCounts: Map<String, Int>,
    val requirementCount: Int,
    val missingAnswerCount: Int,
    val findings: List<RecommendationFinding>,
    val generatedAt: Instant,
    val advisory: Boolean = true,
    val policyVersion: String
)
