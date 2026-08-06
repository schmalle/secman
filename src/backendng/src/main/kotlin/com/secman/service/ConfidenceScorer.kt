package com.secman.service

import com.secman.config.AiRiskAssessmentConfig
import com.secman.domain.ConfidenceBand
import com.secman.domain.SuggestedAnswerType
import jakarta.inject.Singleton

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Maps the LLM's self-reported confidence + groundedness signals to a band.
 *
 * Combined score = 0.5 × self-report
 *                + 0.3 × citation-grounding (each valid citation contributes
 *                        1/3, capped at 3 citations → max 0.3)
 *                + 0.2 × answer-determinism (UNKNOWN → 0, otherwise 1)
 *
 * Then the band is derived from `secman.ai.risk-assessment.confidence.*`
 * thresholds, with one safety rule: HIGH requires ≥1 valid citation. Without
 * a citation, a HIGH score is downgraded to MEDIUM (hallucination guard,
 * spec §FR-3 risk row).
 */
@Singleton
class ConfidenceScorer(private val config: AiRiskAssessmentConfig) {

    fun score(
        modelSelfConfidence: Double,
        validCitationCount: Int,
        answer: SuggestedAnswerType
    ): Pair<Double, ConfidenceBand> {
        val self = modelSelfConfidence.coerceIn(0.0, 1.0)
        val citationsCapped = validCitationCount.coerceIn(0, 3)
        val citationSignal = citationsCapped / 3.0
        val determinism = if (answer == SuggestedAnswerType.UNKNOWN) 0.0 else 1.0

        val raw = (0.5 * self + 0.3 * citationSignal + 0.2 * determinism).coerceIn(0.0, 1.0)

        val band = when {
            raw >= config.confidence.highThreshold && citationsCapped >= 1 -> ConfidenceBand.HIGH
            raw >= config.confidence.mediumThreshold -> ConfidenceBand.MEDIUM
            else -> ConfidenceBand.LOW
        }
        return raw to band
    }
}
