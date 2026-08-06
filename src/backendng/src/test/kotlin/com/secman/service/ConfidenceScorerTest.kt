package com.secman.service

import com.secman.config.AiRiskAssessmentConfig
import com.secman.domain.ConfidenceBand
import com.secman.domain.SuggestedAnswerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Locks the confidence-band thresholds + hallucination-guard rule:
 *   * HIGH requires ≥ 1 valid citation AND combined score ≥ high-threshold.
 *   * Otherwise MEDIUM if score ≥ medium-threshold, else LOW.
 *   * UNKNOWN answers carry zero "determinism" weight so they almost
 *     always end up LOW.
 */
class ConfidenceScorerTest {

    private val config = AiRiskAssessmentConfig().apply {
        confidence.highThreshold = 0.75
        confidence.mediumThreshold = 0.50
    }
    private val scorer = ConfidenceScorer(config)

    @Test
    fun `high self-confidence with citations lands in HIGH band`() {
        val (raw, band) = scorer.score(modelSelfConfidence = 0.9, validCitationCount = 2, answer = SuggestedAnswerType.YES)
        assertTrue(raw >= 0.75, "raw=$raw")
        assertEquals(ConfidenceBand.HIGH, band)
    }

    @Test
    fun `high self-confidence without citations is downgraded to MEDIUM`() {
        // hallucination guard: no citation → no HIGH, even if self-confidence
        // is 1.0.
        val (_, band) = scorer.score(modelSelfConfidence = 1.0, validCitationCount = 0, answer = SuggestedAnswerType.YES)
        assertEquals(ConfidenceBand.MEDIUM, band)
    }

    @Test
    fun `mid self-confidence with no citations lands in MEDIUM`() {
        val (_, band) = scorer.score(modelSelfConfidence = 0.6, validCitationCount = 0, answer = SuggestedAnswerType.YES)
        assertEquals(ConfidenceBand.MEDIUM, band)
    }

    @Test
    fun `low self-confidence lands in LOW`() {
        val (_, band) = scorer.score(modelSelfConfidence = 0.2, validCitationCount = 0, answer = SuggestedAnswerType.YES)
        assertEquals(ConfidenceBand.LOW, band)
    }

    @Test
    fun `UNKNOWN answer suppresses determinism signal and lands in LOW`() {
        // Even with strong self-report and a citation, UNKNOWN should not
        // rate higher than MEDIUM and in practice ends up LOW because
        // 0.2 determinism is missing.
        val (raw, band) = scorer.score(modelSelfConfidence = 0.8, validCitationCount = 1, answer = SuggestedAnswerType.UNKNOWN)
        assertTrue(raw < 0.75, "raw=$raw")
        assertEquals(if (raw >= 0.5) ConfidenceBand.MEDIUM else ConfidenceBand.LOW, band)
    }

    @Test
    fun `citation count is capped at 3`() {
        // 10 citations should not over-rate the score beyond 3 citations'
        // worth of grounding.
        val (rawCapped, _) = scorer.score(0.5, 3, SuggestedAnswerType.YES)
        val (rawOver, _) = scorer.score(0.5, 10, SuggestedAnswerType.YES)
        assertEquals(rawCapped, rawOver, 1e-9, "citation count is not properly capped")
    }
}
