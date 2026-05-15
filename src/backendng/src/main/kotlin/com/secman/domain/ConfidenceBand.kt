package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * Confidence band displayed in the UI. Feature 088.
 *
 * Derived from a combined score on the backend (see ConfidenceScorer):
 *   HIGH    raw ≥ 0.75 AND at least one valid citation.
 *   MEDIUM  0.50 ≤ raw < 0.75, OR raw ≥ 0.75 but no citation (downgrade rule).
 *   LOW     raw < 0.50, OR answer is UNKNOWN.
 *
 * Thresholds are configurable via `secman.ai.risk-assessment.confidence.*`.
 */
@Serdeable
enum class ConfidenceBand {
    HIGH,
    MEDIUM,
    LOW
}
